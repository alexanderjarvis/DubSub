/*
 * Copyright 2013 Alexander Jarvis (@alexanderjarvis) and Panaxiom Ltd (http://panaxiom.co.uk)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.panaxiom.dubsub

import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.collection.mutable.Buffer

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.event.Logging

class DubSub(
  sprayInterval: FiniteDuration,
  drainInterval: FiniteDuration,
  bufferedPublishes: Boolean) extends Actor with ActorLogging {

  import DubSub.Internal._

  val cluster = Cluster(context.system)

  override def preStart(): Unit = cluster.subscribe(self, classOf[MemberEvent])
  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    sprayTask.cancel
    drainTask.cancel
  }

  import context.dispatcher
  val sprayTask = context.system.scheduler.schedule(sprayInterval, sprayInterval, self, Spray)
  val drainTask = context.system.scheduler.schedule(drainInterval, drainInterval, self, PullPlug)

  var nodes = Set.empty[Address]

  var nodeRecepticles = Map.empty[Address, Recepticle].withDefault(a => Recepticle(a, VectorClock(0L, System.currentTimeMillis), Map.empty))

  def localRecepticle = nodeRecepticles(cluster.selfAddress)

  def dubsub(address: Address) = context.actorSelection(self.path.toStringWithAddress(address))

  val publishBuffer = Buffer.empty[(Publish, Long)]

  def receive = {
    case msg @ Subscribe(channel) => {
      subscribe(channel)
      context watch sender
      sender ! msg
    }
    case msg @ Unsubscribe(channel) => {
      unsubscribe(channel)
      context unwatch sender
      sender ! msg
    }
    case p @ Publish(channel, message) => {
      if (bufferedPublishes) {
        publishBuffer += Tuple2(p, System.currentTimeMillis)
      }
      publishLocal(channel, message)
      publishNodes(channel, message)
    }

    // ---- Node Specific ----
    case NodePublish(channel, message) => publishLocal(channel, message)
    case WaterLevels(levels) => {
      val drops = collectDrops(levels)
      if (drops.nonEmpty) {
        sender ! Pour(drops)
      }
      if (otherHasHigherLevels(levels)) {
        sender ! WaterLevels(myLevels)
      }
    }
    case Pour(recepticles) => {
      if (nodes.contains(sender.path.address)) {
        pour(recepticles)
      }
    }
    case Spray => spray()
    case PullPlug => pullPlug()
    case CountSubscriptions => {
      val count = nodeRecepticles.map {
        case (_, recepticle) => recepticle.content.size
      }.sum
      sender ! count
    }

    // ---- Cluster Specific ----
    case state: CurrentClusterState => {
      nodes = state.members.collect { case m if m.status == MemberStatus.Up => m.address }
    }
    case MemberUp(member) => {
      nodes += member.address
    }
    case MemberRemoved(member, previousStatus) => {
      if (member.address == cluster.selfAddress) {
        context stop self
      } else {
        nodes -= member.address
        nodeRecepticles -= member.address
      }
    }
    case e: MemberEvent => //
    case Terminated(ref) => terminated(ref)
    case _ => log.error("Received unknown message")
  }

  private def subscribe(channel: String): Unit = {
    val lr = localRecepticle
    val nextLocalCount = lr.clock.counter + 1
    val currentTime = System.currentTimeMillis
    val newLr = lr.copy(
      clock = lr.clock.copy(counter = nextLocalCount, time = currentTime),
      content = lr.content.get(channel).map { drop =>
        val newDrop = Drop(
          cluster.selfAddress,
          VectorClock(nextLocalCount, currentTime),
          Some(drop.data.map(_ + sender).getOrElse(Set(sender))))
        lr.content.updated(channel, newDrop)
      }.getOrElse {
        lr.content + (channel -> Drop(cluster.selfAddress, VectorClock(nextLocalCount, currentTime), Some(Set(sender))))
      }
    )
    nodeRecepticles += (cluster.selfAddress-> newLr)
  }

  private def unsubscribe(channel: String): Unit = {
    val lr = localRecepticle
    val nextLocalCount = lr.clock.counter + 1
    val currentTime = System.currentTimeMillis
    lr.content.get(channel).map { drop =>
      nodeRecepticles += (cluster.selfAddress -> lr.copy(
        clock = lr.clock.copy(counter = nextLocalCount, time = currentTime),
        content = lr.content.updated(channel, drop.copy(
          clock = drop.clock.copy(counter = nextLocalCount, time = currentTime),
          data = drop.data.map(_.filterNot(_ == sender))))
      ))
    }.getOrElse(log.error("Received Unsubscribe from channel {} which was not subscribed to", channel))
  }

  private def terminated(actorRef: ActorRef): Unit = {
    val lr = localRecepticle
    val nextLocalCount = lr.clock.counter + 1
    val currentTime = System.currentTimeMillis
    nodeRecepticles += (cluster.selfAddress -> lr.copy(
      clock = lr.clock.copy(counter = nextLocalCount, time = currentTime),
      content = lr.content.filterNot { case (_, drop) => drop.data.map(_.contains(actorRef)).getOrElse(false) }
    ))
  }

  private def publishLocal(channel: String, message: String): Unit = {
    localRecepticle.content.get(channel).foreach(_.data.map(_.foreach(sub => sub ! Publish(channel, message))))
  }

  private def publishNodes(channel: String, message: String): Unit = {
    // todo: cache?
    nodeRecepticles.foreach {
      case (address, recepticle) => {
        if (address != cluster.selfAddress) {
          val np = NodePublish(channel, message)
          recepticle.content.get(channel).map { _ =>
            dubsub(address) ! np
          }
        }
      }
    }
  }

  private def pour(recepticles: Iterable[Recepticle]): Unit = {
    var newRecepticles = recepticles.collect { case r if (r.clock.counter > nodeRecepticles(r.address).clock.counter) => r }
    var mergedRecepticles = newRecepticles.map { r =>
      val myRecepticle = nodeRecepticles(r.address)
      (r.address -> myRecepticle.copy(
          clock = myRecepticle.clock.copy(counter = r.clock.counter, time = r.clock.time),
          content = myRecepticle.content ++ r.content)) }
    nodeRecepticles ++= mergedRecepticles

    if (bufferedPublishes) {
      var messagesSent = 0
      publishBuffer.foreach {
        case (publish, time) => {
          newRecepticles.filter(r => r.content.get(publish.channel).map(_.clock.time <= time).getOrElse(false)).foreach { r =>
            dubsub(r.address) ! NodePublish(publish.channel, publish.message)
            messagesSent += 1
          }
        }
      }
      if (messagesSent > 0) {
        log.info("DubSub Publish buffer saved the day with {} messages saved from the blackhole", messagesSent)
      }
    }
  }

  private def collectDrops(levels: Map[Address, Long]) = {
    val filledOtherVersions = myLevels.map { case (k, _) => k -> 0L } ++ levels
    filledOtherVersions.collect {
      case (address, height) if nodeRecepticles(address).clock.counter > height => {
        val recepticle = nodeRecepticles(address)
        val content = recepticle.content.filter {
          case (_, drop) => drop.clock.counter > height
        }
        recepticle.copy(content = content.map { case (key, drop) => (key, drop.copy(data = None)) })
      }
    }
  }

  private def myLevels = {
    nodeRecepticles.map { case (address, recepticle) => (address -> recepticle.clock.counter) }
  }

  private def otherHasHigherLevels(otherLevels: Map[Address, Long]) = {
    otherLevels.exists { case (address, counter) => counter > nodeRecepticles(address).clock.counter }
  }

  private def randomNode: Option[Address] = {
    val otherNodes = nodes.filterNot(_ == cluster.selfAddress).toIndexedSeq
    if (otherNodes.isEmpty) {
      None
    } else {
      val randomIndex = ThreadLocalRandom.current.nextInt(otherNodes.length)
      Some(otherNodes(randomIndex))
    }
  }

  private def spray(): Unit = {
    randomNode.map(node => dubsub(node) ! WaterLevels(myLevels))
  }

  private def pullPlug(): Unit = {
    log.debug("Pulling plug")
    val drainExpiry = drainInterval.toMillis * 2
    nodeRecepticles.foreach {
      case (address, recepticle) => {
        val toRemove = recepticle.content.collect {
          case (key, drop) if (drop.data.map(_.isEmpty).getOrElse(false) && recepticle.clock.time - drop.clock.time > drainExpiry) => key
        }
        if (toRemove.nonEmpty) {
          nodeRecepticles += address -> recepticle.copy(content = recepticle.content -- toRemove)
        }
      }
    }
    if (bufferedPublishes) {
      val timeNow = System.currentTimeMillis
      val messagesToRemove = publishBuffer.filter { case (_, time) => timeNow - time > drainExpiry }
      if (messagesToRemove.nonEmpty) {
        publishBuffer --= messagesToRemove
        log.debug("Removed {} messages from publish buffer with new size {}", messagesToRemove.size, publishBuffer.size)
      }
    }
  }

}

object DubSub {

  def props(
    sprayInterval: FiniteDuration = 500.milliseconds,
    drainInterval: FiniteDuration = 30.second,
    bufferedPublishes: Boolean = true) = {
    Props(classOf[DubSub], sprayInterval, drainInterval, bufferedPublishes)
  }

  private[dubsub] object Internal {
    // Private
    @SerialVersionUID(1L) case object Spray
    @SerialVersionUID(1L) case object PullPlug

    @SerialVersionUID(1L) case class VectorClock(counter: Long, time: Long)
    @SerialVersionUID(1L) case class Drop(address: Address, clock: VectorClock, data: Option[Set[ActorRef]])
    @SerialVersionUID(1L) case class Recepticle(address: Address, clock: VectorClock, content: Map[String, Drop])
    @SerialVersionUID(1L) case class WaterLevels(levels: Map[Address, Long])
    @SerialVersionUID(1L) case class Pour(recepticles: Iterable[Recepticle])

    @SerialVersionUID(1L) case class NodePublish(channel: String, message: String)
  }

}

@SerialVersionUID(1L) case class Subscribe(channel: String)
@SerialVersionUID(1L) case class Unsubscribe(channel: String)
@SerialVersionUID(1L) case object Unsubscribe
@SerialVersionUID(1L) case class Publish(channel: String, message: String)

// For testing
@SerialVersionUID(1L) case object CountSubscriptions
