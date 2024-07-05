import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.*
import scalaz.Scalaz.*

import java.util.concurrent.atomic.*

import flatspec.*
import matchers.*

class ActorSpec extends AnyFlatSpec with should.Matchers:
  it should "wait for windows" in:
    val ref: AtomicReference[Option[Set[Int]]] = AtomicReference(None)
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg >= 5 then
            for {
              _ <- nextWindow[GSet[Int], Int]
              v <- await[GSet[Int], Int](0)
              _ <- liftIO[GSet[Int], Int, Unit](ref.set(Some(v)))
            } yield ()
          else void
      } yield ()

    val handle2: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg >= 6 then
            nextWindow[GSet[Int], Int]
          else void
      } yield ()

    val system = ActorSystem(
      ActorMain.init[GSet[Int], Int](Set.empty)(
        List(handle1 -> Stream(1, 3, 5), handle2 -> Stream(2, 4, 6))
      ),
      "TestSystem"
    )

    @scala.annotation.tailrec
    def result: Set[Int] = ref.get() match
      case Some(v) => v
      case None =>
        Thread.sleep(100)
        result

    assert(result == Set(1, 3, 5, 2, 4, 6))

  it should "not wait if already has the value" in:
    val ref: AtomicReference[Option[Set[Int]]] = AtomicReference(None)
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg == 10 || msg == 20 then
            nextWindow[GSet[Int], Int]
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
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <- nextWindow[GSet[Int], Int]
      } yield ()

    val system = ActorSystem(
      ActorMain.init[GSet[Int], Int](Set.empty)(
        List(handle1 -> Stream(6, 10, 20), handle2 -> Stream(1, 4))
      ),
      "TestSystem"
    )
    @scala.annotation.tailrec
    def result: Set[Int] = ref.get() match
      case Some(v) => v
      case None =>
        Thread.sleep(100)
        result

    assert(result == Set(1, 6, 10))

  it should "queue up messages while waiting" in:
    val ref: AtomicReference[Option[Set[Int]]] = AtomicReference(None)
    val handle1: HandleM[GSet[Int], Int, Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <-
          if msg % 10 == 0 then
            for {
              _ <- nextWindow[GSet[Int], Int]
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
        _ <- modifyCRDT[GSet[Int], Int](gs => gs + msg)
        _ <- nextWindow[GSet[Int], Int]
      } yield ()

    val system = ActorSystem(
      ActorMain.init[GSet[Int], Int](Set.empty)(
        List(handle1 -> Stream(1, 10, 15, 20), handle2 -> Stream(4, 6))
      ),
      "TestSystem"
    )

    @scala.annotation.tailrec
    def result: Set[Int] = ref.get() match
      case Some(v) => v
      case None =>
        Thread.sleep(100)
        result

    assert(result == Set(1, 10, 15, 20, 4, 6))
