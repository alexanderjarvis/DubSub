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
import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.actor._

import uk.co.panaxiom.dubsub._

class AbstractDubSubSpec extends MultiNodeSpec(DubSubSpecConfig)
  with WordSpec with MustMatchers with BeforeAndAfterAll
  with ImplicitSender {

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  def dubsub = system.actorSelection("/user/DubSub")

  def awaitCount(count: Int) {
    awaitAssert {
      dubsub ! CountSubscriptions
      expectMsgType[Int] must be(count)
    }
  }

  def awaitNumSubscribers(channel: String, count: Int) {
    awaitAssert {
      dubsub ! NumSubscribers(channel)
      expectMsgType[Int] must be(count)
    }
  }

  def subscribe(role: akka.remote.testconductor.RoleName) {
    runOn(role) {
      val pubsub = system.actorSelection(node(role) / "user" / "DubSub")
      pubsub ! Subscribe("topic")
      expectMsg(Subscribe("topic"))
    }
  }

  def publish(role: akka.remote.testconductor.RoleName) {
    runOn(role) {
      val pubsub = system.actorSelection(node(role) / "user" / "DubSub")
      pubsub ! Publish("topic", "message")
    }
  }

  def expectPublish {
    awaitAssert {
      expectMsg(Publish("topic", "message"))
    }
  }

  def unsubscribe(role: akka.remote.testconductor.RoleName) {
    runOn(role) {
      val pubsub = system.actorSelection(node(role) / "user" / "DubSub")
      pubsub ! Unsubscribe("topic")
      expectMsg(Unsubscribe("topic"))
    }
  }

}