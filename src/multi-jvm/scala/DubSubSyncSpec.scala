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

    val firstAddress = node(first).address
    val secondAddress = node(second).address

    runOn(first, second) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      Cluster(system) join firstAddress

      system.actorOf(Props[DubSub], "DubSub")

      expectMsgAllOf(
        MemberUp(Member(firstAddress, MemberStatus.Up)),
        MemberUp(Member(secondAddress, MemberStatus.Up)))

      Cluster(system).unsubscribe(testActor)
    }

    testConductor.enter("first & second up")
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
      runOn(first, second) {
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