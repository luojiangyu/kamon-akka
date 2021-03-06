/* =========================================================================================
 * Copyright © 2013 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.akka


import scala.concurrent.duration._
import org.scalatest.concurrent.Eventually
import org.scalactic.TimesOnInt._
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import Metrics._
import kamon.akka.ActorMetricsTestActor._
import kamon.testkit.MetricInspection
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class ActorMetricsSpec extends TestKit(ActorSystem("ActorMetricsSpec")) with WordSpecLike with MetricInspection with Matchers
    with BeforeAndAfterAll with ImplicitSender with Eventually {

  "the Kamon actor metrics" should {
    "respect the configured include and exclude filters" in new ActorMetricsFixtures {
      val trackedActor = createTestActor("tracked-actor")
      actorProcessingTimeMetric.valuesForTag("path") should contain("ActorMetricsSpec/user/tracked-actor")

      val nonTrackedActor = createTestActor("non-tracked-actor")
      actorProcessingTimeMetric.valuesForTag("path") shouldNot contain("ActorMetricsSpec/user/non-tracked-actor")

      val trackedButExplicitlyExcluded = createTestActor("tracked-explicitly-excluded")
      actorProcessingTimeMetric.valuesForTag("path") shouldNot contain("ActorMetricsSpec/user/tracked-explicitly-excluded")
    }

    "not pick up the root supervisor" in {
      actorProcessingTimeMetric.valuesForTag("path") shouldNot contain("ActorMetricsSpec/")
    }


    "record the processing-time of the receive function" in new ActorMetricsFixtures {
      createTestActor("measuring-processing-time", true) ! TrackTimings(sleep = Some(100 millis))

      val timings = expectMsgType[TrackedTimings]
      val processingTimeDistribution = actorProcessingTimeMetric.
        refine(actorTags("ActorMetricsSpec/user/measuring-processing-time")).distribution()

      processingTimeDistribution.count should be(1L)
      processingTimeDistribution.buckets.size should be(1L)
      processingTimeDistribution.buckets.head.value should be(timings.approximateProcessingTime +- 10.millis.toNanos)
    }



    "record the number of errors" in new ActorMetricsFixtures {
      val trackedActor = createTestActor("measuring-errors")
      10.times(trackedActor ! Fail)

      trackedActor ! Ping
      expectMsg(Pong)
      actorErrorsMetric.refine(actorTags("ActorMetricsSpec/user/measuring-errors")).value() should be(10)
    }

    "record the mailbox-size" in new ActorMetricsFixtures {
      val trackedActor = createTestActor("measuring-mailbox-size", true)
      trackedActor ! TrackTimings(sleep = Some(100 millis))
      10.times(trackedActor ! Discard)
      trackedActor ! Ping

      val timings = expectMsgType[TrackedTimings]
      expectMsg(Pong)

      val mailboxSizeDistribution = actorMailboxSizeMetric
        .refine(actorTags("ActorMetricsSpec/user/measuring-mailbox-size")).distribution()

      mailboxSizeDistribution.min should be(0L +- 1L)
      mailboxSizeDistribution.max should be(11L +- 1L)
    }

    "record the time-in-mailbox" in new ActorMetricsFixtures {
      val trackedActor = createTestActor("measuring-time-in-mailbox", true)
      trackedActor ! TrackTimings(sleep = Some(100 millis))
      val timings = expectMsgType[TrackedTimings]

      val timeInMailboxDistribution = actorTimeInMailboxMetric
        .refine(actorTags("ActorMetricsSpec/user/measuring-time-in-mailbox")).distribution()

      timeInMailboxDistribution.count should be(1L)
      timeInMailboxDistribution.buckets.head.frequency should be(1L)
      timeInMailboxDistribution.buckets.head.value should be(timings.approximateTimeInMailbox +- 10.millis.toNanos)
    }

    "clean up the associated recorder when the actor is stopped" in new ActorMetricsFixtures {
      val trackedActor = createTestActor("stop")

      // Killing the actor should remove it's ActorMetrics and registering again bellow should create a new one.
      val deathWatcher = TestProbe()
      deathWatcher.watch(trackedActor)
      trackedActor ! PoisonPill
      deathWatcher.expectTerminated(trackedActor)

      actorProcessingTimeMetric.valuesForTag("path") shouldNot contain("ActorMetricsSpec/user/stop")
    }
  }

  "the Kamon system metrics" should {
    val systemMetrics= Metrics.forSystem(system.name)
    "record deadletters" in {
      val doaActor = system.actorOf(Props[ActorMetricsTestActor], "doa")

      val deathWatcher = TestProbe()
      deathWatcher.watch(doaActor)
      doaActor ! PoisonPill
      deathWatcher.expectTerminated(doaActor)

      (1 to 7).foreach(_ =>  doaActor ! "deadonarrival")
      eventually {
        systemMetrics.deadLetters.value(false).toInt should be(7)
      }
    }

    "record unhandeled messages" in {
      val hndl = system.actorOf(Props[ActorMetricsTestActor], "nonhandled")

      (1 to 10).foreach(_ => hndl ! "CantHandleStrings")
      eventually {
        systemMetrics.unhandledMessages.value(false).toInt should be(10)
      }
    }

    "record active actor counts" in {
      systemMetrics.activeActors.distribution(true)
      (1 to 8).foreach(i => system.actorOf(Props[ActorMetricsTestActor], s"counted-$i"))
      eventually {
        systemMetrics.activeActors.distribution(false).max.toInt should be > 0
      }
    }

    "record processed messages counts" in {
      systemMetrics.processedMessagesByTracked.value(true)
      systemMetrics.processedMessagesByNonTracked.value(true)

      val tracked = system.actorOf(Props[ActorMetricsTestActor], "tracked-actor-counts")
      val nonTracked = system.actorOf(Props[ActorMetricsTestActor], "non-tracked-actor-counts")

      (1 to 10).foreach(_ => tracked ! Discard)
      (1 to 15).foreach(_ => nonTracked ! Discard)

      eventually(systemMetrics.processedMessagesByTracked.value(false) should be(10))
      eventually(systemMetrics.processedMessagesByNonTracked.value(false) should be(15))
    }
  }

  override protected def afterAll(): Unit = shutdown()

  def actorTags(path: String): Map[String, String] =
    Map(
      "path" -> path,
      "system" -> "ActorMetricsSpec",
      "dispatcher" -> "akka.actor.default-dispatcher",
      "class" -> "kamon.akka.ActorMetricsTestActor"
    )

  trait ActorMetricsFixtures {

    def createTestActor(name: String, resetState: Boolean = false): ActorRef = {
      val actor = system.actorOf(Props[ActorMetricsTestActor], name)
      val initialiseListener = TestProbe()

      // Ensure that the router has been created before returning.
      actor.tell(Ping, initialiseListener.ref)
      initialiseListener.expectMsg(Pong)

      // Cleanup all the metric recording instruments:
      if(resetState) {
        val tags = actorTags(s"ActorMetricsSpec/user/$name")

        actorTimeInMailboxMetric.refine(tags).distribution(resetState = true)
        actorProcessingTimeMetric.refine(tags).distribution(resetState = true)
        actorMailboxSizeMetric.refine(tags).distribution(resetState = true)
        actorErrorsMetric.refine(tags).value(resetState = true)
      }

      actor
    }
  }
}
