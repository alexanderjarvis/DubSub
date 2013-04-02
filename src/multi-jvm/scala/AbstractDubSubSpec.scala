import org.scalatest.BeforeAndAfterAll
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender
import akka.actor._

import uk.co.panaxiom.dubsub._

abstract class AbstractDubSubSpec extends MultiNodeSpec(DubSubSpecConfig)
  with WordSpec with MustMatchers with BeforeAndAfterAll
  with ImplicitSender {

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  def subscribe(role: akka.remote.testconductor.RoleName) {
    runOn(role) {
      val pubsub = system.actorFor(node(role) / "user" / "DubSub")
      pubsub ! Subscribe("topic")
      expectMsg(Subscribe("topic"))
    }
  }

  def publish(role: akka.remote.testconductor.RoleName) {
    runOn(role) {
      val pubsub = system.actorFor(node(role) / "user" / "DubSub")
      pubsub ! Publish("topic", "message")
    }
  }

  def expectPublish {
    expectMsg(Publish("topic", "message"))
  }

  def unsubscribe(role: akka.remote.testconductor.RoleName) {
    runOn(role) {
      val pubsub = system.actorFor(node(role) / "user" / "DubSub")
      pubsub ! Unsubscribe("topic")
      expectMsg(Unsubscribe("topic"))
    }
  }

}