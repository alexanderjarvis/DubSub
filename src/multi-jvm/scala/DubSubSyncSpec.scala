import scala.concurrent.duration._
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus

import uk.co.panaxiom.dubsub._

abstract class DubSubSyncSpec extends AbstractDubSubSpec {

  import DubSubSpecConfig._

  "startup cluster" in within(15 seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    val firstAddress = node(first).address
    val secondAddress = node(second).address

    runOn(first) {
      Cluster(system) join firstAddress
      system.actorOf(Props[DubSub], "DubSub")
    }
    runOn(second) {
      Cluster(system) join firstAddress
      system.actorOf(Props[DubSub], "DubSub")
    }

    runOn(first) {
      expectMsgAllOf(
        MemberUp(Member(firstAddress, MemberStatus.Up)),
        MemberUp(Member(secondAddress, MemberStatus.Up)))
    }

    runOn(second) {
      expectMsgAllOf(
        MemberUp(Member(firstAddress, MemberStatus.Up)),
        MemberUp(Member(secondAddress, MemberStatus.Up)))
    }

    Cluster(system).unsubscribe(testActor)

    testConductor.enter("all-up")
  }

  "sync subscriptions" in within(30 seconds) {
    Cluster(system).subscribe(testActor, classOf[MemberUp])
    expectMsgClass(classOf[CurrentClusterState])

    // subscribe on first node
    subscribe(first)

    enterBarrier("sub")

    // bring third node up and publish to first node
    runOn(third) {
      Cluster(system) join node(first).address
      expectMsgAllOf(
        MemberUp(Member(node(first).address, MemberStatus.Up)),
        MemberUp(Member(node(second).address, MemberStatus.Up)),
        MemberUp(Member(node(third).address, MemberStatus.Up)))
      runOn(first) {
        expectMsg(MemberUp(Member(node(third).address, MemberStatus.Up)))
      }
      runOn(second) {
        expectMsg(MemberUp(Member(node(third).address, MemberStatus.Up)))
      }
    }

    enterBarrier("wait")

    runOn(third) {
      system.actorOf(Props[DubSub], "DubSub") ! HubSynced
      expectMsg(20 seconds, HubSynced)

      publish(third)
      runOn(first) {
        expectPublish
      }
    }

    Cluster(system).unsubscribe(testActor)
  }

}

class DubSubSyncSpecMultiJvmNode1 extends DubSubSyncSpec
class DubSubSyncSpecMultiJvmNode2 extends DubSubSyncSpec
class DubSubSyncSpecMultiJvmNode3 extends DubSubSyncSpec