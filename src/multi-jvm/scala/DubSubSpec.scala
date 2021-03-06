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

abstract class DubSubSpec extends AbstractDubSubSpec {

  import DubSubSpecConfig._

  "startup cluster" in within(15 seconds) {

    Cluster(system) join node(first).address

    system.actorOf(DubSub.props(bufferedPublishes = false), "DubSub")

    testConductor.enter("all up")
  }

  "subscribe" in within(15 seconds) {
    enterBarrier("subscribe")
    subscribe(first)
    subscribe(second)
    subscribe(third)
    awaitCount(3)
  }

  "publish" in within(15 seconds) {
    enterBarrier("publish")
    publish(first)
    expectPublish
    runOn(first) {
      expectMsg(Published(3))
    }
  }

  "unsubscribe" in within(15 seconds) {
    enterBarrier("unsubscribe")
    unsubscribe(first)
    unsubscribe(second)
    unsubscribe(third)
  }

  "num subscribers" in within(15 seconds) {
    enterBarrier("num subscribers")
    awaitNumSubscribers("topic", 0)
    subscribe(first)
    subscribe(second)
    subscribe(third)
    awaitNumSubscribers("topic", 1)
    unsubscribe(first)
    unsubscribe(second)
    unsubscribe(third)
    awaitNumSubscribers("topic", 0)
  }

}

class DubSubSpecMultiJvmNode1 extends DubSubSpec
class DubSubSpecMultiJvmNode2 extends DubSubSpec
class DubSubSpecMultiJvmNode3 extends DubSubSpec