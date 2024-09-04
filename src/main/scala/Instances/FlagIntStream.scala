package Instances

import Types.PersistStream

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

case class FlagIntStream(
    val i: Int,
    val flag: AtomicBoolean,
    val tracker: AtomicInteger,
):
  def setFlag = flag.set(true)

object FlagIntStream:
  def newFlagIntStream(tracker: AtomicInteger): FlagIntStream =
    tracker.set(0)
    FlagIntStream(0, new AtomicBoolean(false), tracker)

given [A]: PersistStream[FlagIntStream, Int] with
  extension (x: FlagIntStream)
    def next: (Option[Int], FlagIntStream) =
      if x.flag.get() then (None, x)
      else
        x.tracker.incrementAndGet()
        (Some(x.i), x.copy(i = x.i + 1))

  def empty: FlagIntStream =
    val fis = FlagIntStream.newFlagIntStream(new AtomicInteger)
    fis.setFlag
    fis
