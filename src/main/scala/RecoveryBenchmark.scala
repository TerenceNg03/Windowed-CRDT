package wcrdt

import Instances.FlagIntStream
import Instances.{*, given}
import Types.ActorMain
import Types.HandleM
import Types.HandleM.*
import Types.given
import cats.syntax.all.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.DispatcherSelector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import io.circe.*
import io.circe.syntax.*
import io.circe.generic.auto.*

case class Failure(
    val delay: Int,
    val actors: List[Int]
)

case class RecConfig(
    val totalTime: Int,
    val actors: Int,
    val output: String,
    val sampleInterval: Int,
    val failures: List[Failure],
    val shouldWait: Boolean
)

val handle: AtomicBoolean => Boolean => HandleM[GCounter[
  Double,
  ProcId
], Int, FlagIntStream, Unit] = b => wait =>
  for {
    msg <- getMsg
    procId <- getProcId
    _ <-
      if b.compareAndSet(true, false) then
        for {
          _ <- liftIO(Thread.sleep(1000))
          _ <- error("Introduced Failure")
        } yield ()
      else pure(())
    _ <- modifyCRDT[GCounter[Double, ProcId], Int, FlagIntStream](gs =>
      gs.increase(procId)(1)
    )
    _ <- liftIO(Thread.sleep(4))
    v <- getLocalState
    _ <-
      if v.value.toInt % 80 == 0 then
        for {
          _ <- nextWindow[GCounter[Double, ProcId], Int, FlagIntStream]
          w <- currentWindow
          _ <-
            (wait, w) match
              case (false, _) => pure(())
              case (true, 0) => await(0) >> pure(())
              case (true, _) => await(w-1) >> pure(())
        } yield ()
      else pure(())
  } yield ()

val stopFlag = new AtomicBoolean(false)

var measures: List[(Long, Long, Long, Long)] = List()
val measure = (trackers: List[AtomicInteger]) =>
  (interval: Int) =>
    Thread(() => {
      val init = System.currentTimeMillis()
      var prev = List.fill(trackers.length)(0)
      var t0 = init
      Thread.sleep(interval)
      while (!stopFlag.get()) {
        val now = System.currentTimeMillis()
        prev = trackers
          .zip(LazyList.from(0))
          .zip(prev)
          .map {
            case ((x, i), x0) => {
              val n = x.get()
              measures = (now - init, now - t0, i, n - x0) :: measures
              n
            }
          }
          .toList
        t0 = now
        Thread.sleep(interval)
      }
    })

val terminate = (streams: List[FlagIntStream]) =>
  (totalTime: Int) =>
    Thread(() => {
      Thread.sleep(totalTime * 1000)
      streams.foreach(x => x.setFlag)
      stopFlag.set(true)
    })

val failure = (failFlags: List[AtomicBoolean]) =>
  (failures: List[Failure]) =>
    Thread(() => {
      failures.foreach(f =>
        Thread.sleep(1000 * f.delay)
        f.actors.foreach(i => failFlags(i).set(true))
      )
    })

@main def mainRec(args: String*) =
  val cfgStr =
    if args.length >= 1 then scala.io.Source.fromFile(args.head).mkString
    else scala.io.Source.fromResource("rec_cfg.yaml").mkString

  val cfg: RecConfig = yaml.parser
    .parse(cfgStr)
    .leftMap(err => err: Error)
    .flatMap(_.as[RecConfig])
    .valueOr(throw _)

  val nActor = cfg.actors
  val trackers = List.fill(nActor)(new AtomicInteger)
  val streams = trackers map (FlagIntStream.newFlagIntStream)
  val failFlags = List.fill(nActor)(new AtomicBoolean(false))

  val start = System.currentTimeMillis()

  val _ = ActorSystem(
    ActorMain.withDispatcher[GCounter[Double, ProcId], Int, FlagIntStream](
      GCounter.newGCounter[Double, ProcId]
    )(
      streams zip failFlags map ((s, f) => handle(f)(cfg.shouldWait) -> s)
    )(DispatcherSelector.fromConfig("benchmark-dispatcher")),
    "TestSystem"
  )

  val terminateT = terminate(streams)(cfg.totalTime)
  val measureT = measure(trackers)(cfg.sampleInterval)
  val failureT = failure(failFlags)(cfg.failures)
  terminateT.start()
  measureT.start()
  failureT.start()

  terminateT.join()

  val end = System.currentTimeMillis()
  measureT.join()
  failureT.join()

  println(s"Total time: ${(end - start) / 1000}s")

  val fw = java.io.FileWriter(cfg.output, false)
  fw.write(
    measures
      .map((t, d, i, x) =>
        Map.from(
          List(
            "time" -> t,
            "duration" -> d,
            "actor" -> i.toLong,
            "msgs" -> x.toLong
          )
        )
      )
      .asJson
      .show
  )
  fw.flush()
  fw.close()
