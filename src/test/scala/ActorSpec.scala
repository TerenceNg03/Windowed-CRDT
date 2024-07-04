import Instances.{_, given}
import Types.ActorMain
import Types.CRDT
import Types.HandleM
import Types.HandleM._
import Types.given
import Utils.runSystem
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest._
import scalaz.Scalaz._

import java.util.concurrent.atomic._
import cats.effect.concurrent._

import flatspec._
import matchers._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import cats.effect.IO

object Utils:
  def runSystem[A, B, M](using x: CRDT[A, B, Int])(
      l: List[HandleM[A, M, Unit]]
  )(events: List[(Int, M)]) =
    val system: ActorSystem[(Int, M)] =
      ActorSystem(ActorMain.init[A, B, M](l), "TestSystem")
    events.foreach(e =>
      system ! e
      Thread.sleep(100)
    )

class ActorSpec extends AnyFlatSpec with should.Matchers:
  it should "wait for windows" in:
    val ref: AtomicReference[Option[Set[Int]]] = AtomicReference(None)
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.update(_ + msg))
        _ <-
          if msg >= 5 then
            for {
              _ <- modifyCRDT[GSet[Int], Int](gs => gs.nextWindow())
              v <- await[GSet[Int], Int](0)
              _ <- liftIO[GSet[Int], Int, Unit](ref.set(Some(v)))
            } yield ()
          else void
      } yield ()

    val handle2: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.update(_ + msg))
        _ <-
          if msg >= 6 then
            modifyCRDT[GSet[Int], Int](gs => gs.nextWindow()) >> void
          else void
      } yield ()

    runSystem(List(handle1, handle2))(
      List((1, 1), (1, 3), (1, 5), (2, 2), (2, 4), (2, 6))
    )

    @scala.annotation.tailrec
    def result: Set[Int] = ref.get() match
      case Some(v) => v
      case None    => result

    assert(result == Set(1, 3, 5, 2, 4, 6))

  it should "not wait if already has the value" in:
    val ref: AtomicReference[Option[Set[Int]]] = AtomicReference(None)
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.update(_ + msg))
        _ <-
          if msg == 10 || msg == 20 then
            modifyCRDT[GSet[Int], Int](gs => gs.nextWindow()) >> void
          else void
        _ <-
          if msg == 20 then
            for {
              _ <- await[GSet[Int], Int](1)
              v <- await[GSet[Int], Int](0)
              _ <- liftIO[GSet[Int], Int, Unit](ref.set(Some(v)))
            } yield ()
          else void
      } yield ()

    val handle2: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.update(_ + msg))
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.nextWindow())
      } yield ()

    runSystem(List(handle1, handle2))(
      List((2, 1), (1, 6), (1, 10), (1, 20), (2, 4))
    )

    @scala.annotation.tailrec
    def result: Set[Int] = ref.get() match
      case Some(v) => v
      case None    => result

    assert(result == Set(1, 6, 10))

  it should "queue up messages while waiting" in:
    val ref: AtomicReference[Option[Set[Int]]] = AtomicReference(None)
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.update(_ + msg))
        _ <-
          if msg % 10 == 0 then
            for {
              _ <- modifyCRDT[GSet[Int], Int](gs => gs.nextWindow())
              v <- await[GSet[Int], Int](0)
            } yield ()
          else void
        _ <-
          if msg == 20 then
            for {
              v <- await[GSet[Int], Int](1)
              _ <- liftIO[GSet[Int], Int, Unit](ref.set(Some(v)))
            } yield ()
          else void
      } yield ()

    val handle2: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.update(_ + msg))
        _ <- modifyCRDT[GSet[Int], Int](gs => gs.nextWindow())
      } yield ()

    runSystem(List(handle1, handle2))(
      List((1, 1), (1, 10), (1, 15), (1, 20), (2, 4), (2, 6))
    )

    @scala.annotation.tailrec
    def result: Set[Int] = ref.get() match
      case Some(v) => v
      case None    => result

    assert(result == Set(1, 10, 15, 20, 4, 6))
