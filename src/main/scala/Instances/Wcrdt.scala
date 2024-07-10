package Instances

import Instances.given
import Types.CRDT

/** Windowed CRDT from a CRDT
  *
  * Window are numbered from 0. (After the first call of nextWindow #window
  * would be 1)
  *
  * @param procId
  * @param local
  * @param l
  * @param w
  * @param window
  */

type ProcId = Int
type WindowId = Int
case class SharedWcrdt[A, R](
    val innerCRDT: LocalWin[Map[ProcId, A]],
    val finished: GSet[(WindowId, ProcId)],
    val globalProgress: GMap[WindowId, (A, GMap[ProcId, LastWriteWin[R]])],
    val windows: LocalWin[Map[ProcId, WindowId]]
):
  def delegate(procId: ProcId)(
      procList: IterableOnce[ProcId]
  )(remoteWindow: WindowId)(remoteCRDT: A): (WindowId, SharedWcrdt[A, R]) =
    val (w, crdt) = latestWindow(procList).getOrElse(remoteWindow, remoteCRDT)
    w -> this.copy(
      innerCRDT = LocalWin(innerCRDT.v.updated(procId, crdt)),
      windows = LocalWin(windows.v.updated(procId, w))
    )

  def latestWindow(procList: IterableOnce[ProcId]): Option[(WindowId, A)] =
    Range(0, windows.v.values.maxOption.getOrElse(0)).reverse
      .map(x => query(x)(procList).map(y => (x + 1, y)))
      .flatten
      .headOption

  def nextWindow[B](
      procId: ProcId
  )(nextMsgRef: R)(using x: CRDT[A]): SharedWcrdt[A, R] =
    windows.v
      .get(procId)
      .map(window =>
        val updatedProgress = globalProgress get (window) match
          case Some(a) =>
            a \/ (innerCRDT.v(procId), Map(
              procId -> LastWriteWin.newLWW(nextMsgRef)
            ))
          case None =>
            (
              innerCRDT.v(procId),
              Map(procId -> LastWriteWin.newLWW(nextMsgRef))
            )
        this.copy(
          finished = finished + (window -> procId),
          globalProgress = globalProgress updated (window, updatedProgress),
          windows = LocalWin(windows.v.updatedWith(procId)(w => w.map(_ + 1)))
        )
      )
      .getOrElse(this)

  def query(w: Int)(procList: IterableOnce[ProcId]): Option[A] =
    val ok =
      procList.iterator.map(proc => (w, proc)) forall (x => finished contains x)
    if ok then Some(globalProgress(w)._1) else None

  def update(procId: ProcId)(f: A => A) = this.copy(innerCRDT =
    LocalWin(
      innerCRDT.v.updatedWith(procId)((v: Option[A]) => v.map(x => f(x)))
    )
  )

object SharedWcrdt:
  def newSharedWcrdt[A, R]: SharedWcrdt[A, R] =
    SharedWcrdt(
      innerCRDT = LocalWin(Map.empty),
      finished = Set.empty,
      globalProgress = Map.empty,
      windows = LocalWin(Map.empty)
    )
given [A: CRDT, R]: CRDT[SharedWcrdt[A, R]] with
  extension (x: SharedWcrdt[A, R])
    def \/(y: SharedWcrdt[A, R]): SharedWcrdt[A, R] =
      SharedWcrdt(
        innerCRDT = x.innerCRDT \/ y.innerCRDT,
        globalProgress = x.globalProgress \/ y.globalProgress,
        finished = x.finished \/ y.finished,
        windows = x.windows \/ y.windows
      )
