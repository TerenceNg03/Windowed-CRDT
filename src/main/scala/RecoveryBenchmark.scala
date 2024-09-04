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
    val failures: List[Failure]
)

val handle: AtomicBoolean => HandleM[GCounter[
  Double,
  ProcId
], Int, FlagIntStream, Unit] = b =>
  for {
    msg <- getMsg
    procId <- getProcId
    _ <-
      if b.compareAndSet(true, false) then error("Introduced Failure")
      else pure(())
    _ <- modifyCRDT[GCounter[Double, ProcId], Int, FlagIntStream](gs =>
      gs.increase(procId)(msg)
    )
  } yield ()

val stopFlag = new AtomicBoolean(false)

var measures: List[(Long, Int)] = List()
val measure = (trackers: List[AtomicInteger]) => (interval: Int) =>
  Thread(() => {
    while (!stopFlag.get()) {
      val processed = trackers.map(x => x.get()).sum
      measures = (System.currentTimeMillis(), processed) :: measures
      Thread.sleep(interval)
    }
  })

val terminate = (streams: List[FlagIntStream]) => (totalTime: Int) =>
  Thread(() => {
    Thread.sleep(totalTime * 1000)
    streams.foreach(x => x.setFlag)
    stopFlag.set(true)
  })

val failure = (failFlags: List[AtomicBoolean]) =>
  (failures: List[Failure]) =>
    Thread(() => {
      failures.foreach(f =>
        Thread.sleep(1000*f.delay)
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
      streams zip failFlags map ((s, f) => handle(f) -> s)
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
  fw.write(measures.asJson.show)
  fw.flush()
  fw.close()
