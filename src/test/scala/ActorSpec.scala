import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.*

import java.util.concurrent.atomic.AtomicBoolean

import flatspec.*
import matchers.*

class ActorSpec extends AnyFlatSpec with should.Matchers:
  it should "wait for windows" in:
    val result: MVar[Set[Int]] = MVar.newMVar
    val handle1: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <-
          if msg >= 5 then
            for {
              _ <- nextWindow[GSet[Int], Int, ListStream[Int]]
              v <- await[GSet[Int], Int, ListStream[Int]](0)
              _ <- liftIO[GSet[Int], Int, ListStream[Int], Unit](result.put(v))
            } yield ()
          else point(())
      } yield ()

    val handle2: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <-
          if msg >= 6 then nextWindow[GSet[Int], Int, ListStream[Int]]
          else point(())
      } yield ()

    val _ = ActorSystem(
      ActorMain.init[GSet[Int], Int, ListStream[Int]](Set.empty)(
        List(handle1 -> ListStream(List(1, 3, 5)), handle2 -> ListStream(List(2, 4, 6)))
      ),
      "TestSystem"
    )

    assert(result.get() == Set(1, 3, 5, 2, 4, 6))

  it should "not wait if already has the value" in:
    val result: MVar[Set[Int]] = MVar.newMVar
    val handle1: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <-
          if msg == 10 || msg == 20 then nextWindow[GSet[Int], Int, ListStream[Int]]
          else point(())
        _ <-
          if msg == 20 then
            for {
              _ <- await[GSet[Int], Int, ListStream[Int]](1)
              v <- await[GSet[Int], Int, ListStream[Int]](0)
              _ <- liftIO[GSet[Int], Int, ListStream[Int], Unit](result.put(v))
            } yield ()
          else point(())
      } yield ()

    val handle2: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <- nextWindow[GSet[Int], Int, ListStream[Int]]
      } yield ()

    val _ = ActorSystem(
      ActorMain.init[GSet[Int], Int, ListStream[Int]](Set.empty)(
        List(handle1 -> ListStream(List(6, 10, 20)), handle2 -> ListStream(List(1, 4)))
      ),
      "TestSystem"
    )

    assert(result.get() == Set(1, 6, 10))

  it should "clear queue after continuing" in:
    val result: MVar[Set[Int]] = MVar.newMVar
    val handle1: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <-
          if msg % 10 == 0 then
            for {
              _ <- nextWindow[GSet[Int], Int, ListStream[Int]]
              _ <- await[GSet[Int], Int, ListStream[Int]](0)
            } yield ()
          else point(())
        _ <-
          if msg == 20 then
            for {
              v <- await[GSet[Int], Int, ListStream[Int]](1)
              _ <- liftIO[GSet[Int], Int, ListStream[Int], Unit](result.put(v))
            } yield ()
          else point(())
      } yield ()

    val handle2: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <- nextWindow[GSet[Int], Int, ListStream[Int]]
      } yield ()

    val _ = ActorSystem(
      ActorMain.init[GSet[Int], Int, ListStream[Int]](Set.empty)(
        List(handle1 -> ListStream(List(1, 10, 15, 20)), handle2 -> ListStream(List(4, 6)))
      ),
      "TestSystem"
    )

    assert(result.get() == Set(1, 10, 15, 20, 4, 6))

  it should "recover from crashes" in:
    val flag = new AtomicBoolean(true)
    val result = MVar.newMVar[Set[Int]]

    val handle1: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <-
          if msg >= 5 then
            for
              _ <- nextWindow[GSet[Int], Int, ListStream[Int]]
              v <- await[GSet[Int], Int, ListStream[Int]](0)
              _ <- liftIO(result.put(v))
            yield ()
          else point(())
      } yield ()

    val handle2: HandleM[GSet[Int], Int, ListStream[Int], Unit] =
      for {
        msg <- getMsg
        _ <- modifyCRDT[GSet[Int], Int, ListStream[Int]](gs => gs + msg)
        _ <-
          if flag.getAndSet(false) then error("test crash")
          else point(())
        _ <-
          if msg >= 6 then nextWindow[GSet[Int], Int, ListStream[Int]]
          else point(())
      } yield ()

    val _ = ActorSystem(
      ActorMain.init[GSet[Int], Int, ListStream[Int]](Set.empty)(
        List(handle1 -> ListStream(List(1, 3, 5)), handle2 -> ListStream(List(2, 4, 6)))
      ),
      "TestSystem"
    )

    assert(result.get() == Set(1, 2, 3, 4, 5, 6))
