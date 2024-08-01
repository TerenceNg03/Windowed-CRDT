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
case class Wcrdt[A, R](
    val innerCRDT: LocalWin[A],
    val finished: GMap[ProcId, MaxNum[WindowId]],
    val globalProgress: GMap[WindowId, (A, GMap[ProcId, LastWriteWin[R]])],
    val window: LocalWin[WindowId]
):
  def exec(procId: ProcId)(
      procList: Set[ProcId]
  )(remoteWindow: WindowId)(remoteCRDT: A): (WindowId, Wcrdt[A, R]) =
    val (w, crdt) = latestWindow(procList)
      .flatMap((w, crdt) =>
        if remoteWindow > w then None
        else Some((w, crdt))
      )
      .getOrElse(remoteWindow, remoteCRDT)
    w -> this.copy(
      innerCRDT = LocalWin(crdt),
      window = LocalWin(w)
    )

  def latestWindow(procList: Set[ProcId]): Option[(WindowId, A)] =
    val w = procList.map(x => finished.get(x).map(x => x.v).getOrElse(-1)).min
    if w >= 0 then Some(w -> globalProgress(w)._1)
    else None

  def nextWindow[B](
      procId: ProcId
  )(nextMsgRef: R)(using x: CRDT[A]): Wcrdt[A, R] =
    val updatedProgress = globalProgress get (window.v) match
      case Some(a) =>
        a \/ (innerCRDT.v, Map(
          procId -> LastWriteWin.newLWW(nextMsgRef)
        ))
      case None =>
        (
          innerCRDT.v,
          Map(procId -> LastWriteWin.newLWW(nextMsgRef))
        )
    this.copy(
      finished = finished.updated(procId, MaxNum(window.v)),
      globalProgress = globalProgress updated (window.v, updatedProgress),
      window = LocalWin(window.v + 1)
    )

  def query(w: Int)(procList: Set[ProcId]): Option[A] =
    assert(w >= 0)
    val wMax =
      procList.map(x => finished.get(x).map(x => x.v).getOrElse(-1)).min
    if w <= wMax then Some(globalProgress(w)._1)
    else None

  def update(f: A => A) = this.copy(innerCRDT = LocalWin(f(innerCRDT.v)))

object Wcrdt:
  def newWcrdt[A, R](initCRDT: A): Wcrdt[A, R] =
    Wcrdt(
      innerCRDT = LocalWin(initCRDT),
      finished = Map.empty,
      globalProgress = Map.empty,
      window = LocalWin(0)
    )
given [A: CRDT, R]: CRDT[Wcrdt[A, R]] with
  extension (x: Wcrdt[A, R])
    def \/(y: Wcrdt[A, R]): Wcrdt[A, R] =
      Wcrdt(
        innerCRDT = x.innerCRDT \/ y.innerCRDT,
        globalProgress = x.globalProgress \/ y.globalProgress,
        finished = x.finished \/ y.finished,
        window = x.window \/ y.window
      )
