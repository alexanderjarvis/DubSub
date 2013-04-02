import scala.concurrent.duration._
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus

import uk.co.panaxiom.dubsub._

abstract class DubSubSpec extends AbstractDubSubSpec {

  import DubSubSpecConfig._

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

  "publish" in within(15 seconds) {
    enterBarrier("publish")
    publish(first)
    expectPublish
  }

  "unsubscribe" in within(15 seconds) {
    enterBarrier("unsubscribe")
    unsubscribe(first)
    unsubscribe(second)
    unsubscribe(third)
  }

}

class DubSubSpecMultiJvmNode1 extends DubSubSpec
class DubSubSpecMultiJvmNode2 extends DubSubSpec
class DubSubSpecMultiJvmNode3 extends DubSubSpec