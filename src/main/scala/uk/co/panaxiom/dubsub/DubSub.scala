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

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.event.Logging
import scala.collection.mutable.HashMap
import scala.collection.immutable.Set
import scala.util.Random

class DubSub extends Actor with ActorLogging {

  val cluster = Cluster(context.system)

  var hubs = IndexedSeq.empty[ActorRef]

  override def preStart(): Unit = cluster.subscribe(self, classOf[MemberUp])
  override def postStop(): Unit = cluster.unsubscribe(self)

  val localSubscriptions = new HashMap[String, Set[ActorRef]]()
  val hubSubscriptions = new HashMap[String, Set[ActorRef]]()

  var synced = false
  var syncWatch = IndexedSeq.empty[ActorRef]

  def dubsub(member: Member) = context.actorFor(RootActorPath(member.address) / "user" / "DubSub")

  def receive = {
    case Subscribe(channel) => {
      subscribe(channel, localSubscriptions)
      hubs.foreach(hub => hub ! HubSubscribe(channel))
      sender ! Subscribe(channel)
    }
    case Unsubscribe(channel) => {
      unsubscribe(channel, localSubscriptions)
      hubs.foreach(hub => hub ! HubUnsubscribe(channel))
      sender ! Unsubscribe(channel)
    }
    case Publish(channel, message) => {
      publishLocal(channel, message)
      publishHubs(channel, message)
    }

    // ---- Hub Specific ----
    case HubSubscribe(channel) => subscribe(channel, hubSubscriptions)
    case HubUnsubscribe(channel) => unsubscribe(channel, hubSubscriptions)
    case HubPublish(channel, message) => publishLocal(channel, message)
    case HubSubscriptions => {
      log.info("Sending subscriptions")
      sender ! HubSubscriptions(hubSubscriptions.toMap)
    }
    case HubSubscriptions(subs) => {
      if (synced) {
        log.info("Already synced")
      } else {
        synced = true
        log.info("Syncing subscriptions")
        hubSubscriptions ++= subs // todo: merge maps
        syncWatch foreach ( _ ! HubSynced )
      }
    }
    case HubSynced => {
      context watch sender
      syncWatch = syncWatch :+ sender
      if (synced) sender ! HubSynced
    }

    // ---- Cluster Specific ----
    case state: CurrentClusterState => {
      synced = false
      val upMembers = state.members.filter(_.status == MemberStatus.Up)
      upMembers foreach register
      val otherMembers = upMembers.filterNot(_.address == self.path.address).toIndexedSeq
      if (otherMembers.length > 0) {
        val member = otherMembers(Random.nextInt(otherMembers.length) % otherMembers.length)
        dubsub(member) ! HubSubscriptions
      }
    }
    case MemberUp(member) => register(member)
    case HubRegistration if !hubs.contains(sender) => {
      context watch sender
      hubs = hubs :+ sender
      log.info("(" + hubs.size + ") nodes in DubSub cluster")
      if (!synced && sender != self) sender ! HubSubscriptions
    }
    case Terminated(a) => {
      hubs = hubs.filterNot(_ == a)
      hubSubscriptions filter(_._2.contains(a)) foreach { subs =>
        hubSubscriptions.update(subs._1, subs._2.filterNot(_ == a))
      }
      log.info("(" + hubs.size + ") nodes in DubSub cluster")
    }
    case _ => log.error("received unknown message")
  }

  private def register(member: Member) {
    dubsub(member) ! HubRegistration
  }

  private def publishLocal(channel: String, message: String) {
    localSubscriptions.get(channel).map(_.foreach(sub => sub ! Publish(channel, message)))
  }

  private def publishHubs(channel: String, message: String) {
    hubSubscriptions.get(channel).map(_.filterNot(_ == self).foreach(hub => hub ! HubPublish(channel, message)))
  }

  private def subscribe(channel: String, subscriptions: HashMap[String, Set[ActorRef]]) {
    subscriptions += (channel -> (Set(sender) ++ subscriptions.get(channel).getOrElse(Set())))
  }

  private def unsubscribe(channel: String, subscriptions: HashMap[String, Set[ActorRef]]) {
    subscriptions += (channel -> subscriptions.get(channel).filterNot(_ == sender).getOrElse(Set()))
    if (subscriptions.get(channel).isEmpty) subscriptions -= channel
  }

}

@SerialVersionUID(1L) case class Subscribe(channel: String)
@SerialVersionUID(1L) case class Unsubscribe(channel: String)
@SerialVersionUID(1L) case object Unsubscribe
@SerialVersionUID(1L) case class Publish(channel: String, message: String)

// For testing
@SerialVersionUID(1L) case object HubSynced

// Private
@SerialVersionUID(1L) private case object HubRegistration
@SerialVersionUID(1L) private case class HubSubscribe(channel: String)
@SerialVersionUID(1L) private case class HubUnsubscribe(channel: String)
@SerialVersionUID(1L) private case class HubPublish(channel: String, message: String)
@SerialVersionUID(1L) private case object HubSubscriptions
@SerialVersionUID(1L) private case class HubSubscriptions(subscriptions: Map[String, Set[ActorRef]])

