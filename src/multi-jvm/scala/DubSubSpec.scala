import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus

import uk.co.panaxiom.dubsub._

abstract class DubSubSpec extends MultiNodeSpec(DubSubSpecConfig)
  with WordSpec with MustMatchers with BeforeAndAfterAll
  with ImplicitSender {

  import DubSubSpecConfig._

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  "startup cluster" in within(15 seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    val firstAddress = node(first).address
    val secondAddress = node(second).address
    val thirdAddress = node(third).address

    Cluster(system) join firstAddress

    system.actorOf(Props[DubSub], "DubSub")

    expectMsgAllOf(
      MemberUp(Member(firstAddress, MemberStatus.Up)),
      MemberUp(Member(secondAddress, MemberStatus.Up)),
      MemberUp(Member(thirdAddress, MemberStatus.Up)))

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-up")
  }

  "subscribe" in within(15 seconds) {
    enterBarrier("subscribe")
    subscribe(first)
    subscribe(second)
    subscribe(third)
  }

  def subscribe(role: akka.remote.testconductor.RoleName) {
    runOn(role) {
      val pubsub = system.actorFor(node(role) / "user" / "DubSub")
      pubsub ! Subscribe("topic")
      expectMsg(Subscribe("topic"))
    }
  }

  "publish" in within(15 seconds) {
    enterBarrier("publish")
    runOn(first) {
      publish
    }
    expectPublish
  }

  def publish {
    val pubsub = system.actorFor("/user/DubSub")
    pubsub ! Publish("topic", "message")
  }

  def expectPublish {
    expectMsg(Publish("topic", "message"))
  }

  "unsubscribe" in within(15 seconds) {
    enterBarrier("unsubscribe")
    unsubscribe(first)
    unsubscribe(second)
    unsubscribe(third)

    def unsubscribe(role: akka.remote.testconductor.RoleName) {
      runOn(role) {
        val pubsub = system.actorFor(node(role) / "user" / "DubSub")
        pubsub ! Unsubscribe("topic")
        expectMsg(Unsubscribe("topic"))
      }
    }
  }

  "sync subscriptions" in within(15 seconds) {
    enterBarrier("wait for nodes")
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    // bring third node offline
    runOn(third) {
      Cluster(system).subscribe(testActor, classOf[MemberDowned])
      Cluster(system) down node(third).address
      runOn(first) {
        expectMsg(MemberDowned(Member(node(third).address, MemberStatus.Down)))
      }
    }

    // subscribe on first node
    subscribe(first)

    // bring third node up and publish to first node
    runOn(third) {
      Cluster(system) join node(first).address
      runOn(first) {
        expectMsg(MemberUp(Member(node(third).address, MemberStatus.Up)))
      }

      val pubsub = system.actorFor(node(third) / "user" / "DubSub")
      pubsub ! Publish("topic", "message")
      runOn(first) {
        expectPublish
      }
    }

    Cluster(system).unsubscribe(testActor)
  }

}

class DubSubSpecMultiJvmNode1 extends DubSubSpec
class DubSubSpecMultiJvmNode2 extends DubSubSpec
class DubSubSpecMultiJvmNode3 extends DubSubSpec