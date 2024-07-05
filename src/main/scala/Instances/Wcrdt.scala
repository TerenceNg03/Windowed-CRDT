package Instances

import Instances.given
import Types.CRDT

/** Windowed CRDT from a CRDT
  *
  * Window are numbered from 0. (After the first call of nextWindow #window
  * would be 1)
  *
  * @param procID
  * @param local
  * @param l
  * @param w
  * @param window
  */

type ProcID = Int
type WindowID = Int
case class Wcrdt[A, R](
    val procID: LocalWin[ProcID],
    val innerCRDT: LocalWin[A],
    val finished: GSet[(WindowID, ProcID)],
    val globalProgress: GMap[WindowID, (A, GMap[ProcID, LastWriteWin[R]])],
    val window: LocalWin[WindowID],
    val msgRef: LocalWin[R]
):
  def nextWindow[B]()(using x: CRDT[A]): Wcrdt[A, R] =
    val updatedProgress = globalProgress get (window.v) match
      case Some(a) =>
        a \/ (innerCRDT.v, Map(procID.v -> LastWriteWin.newLWW(msgRef.v)))
      case None => (innerCRDT.v, Map(procID.v -> LastWriteWin.newLWW(msgRef.v)))
    this.copy(
      finished = finished + (window.v -> procID.v),
      globalProgress = globalProgress updated (window.v, updatedProgress),
      window = LocalWin(window.v + 1)
    )

  def query(w: Int)(procList: IterableOnce[ProcID]): Option[A] =
    val ok = procList map (proc => (w, proc)) forall (x => finished contains x)
    if ok then Some(globalProgress(w)._1) else None

  def update(f: A => A) = this.copy(innerCRDT = LocalWin(f(innerCRDT.v)))

object Wcrdt:
  def newWcrdt[A, R](procID: ProcID)(initCRDT: A)(initMsgRef: R) =
    Wcrdt(
      procID = LocalWin(procID),
      innerCRDT = LocalWin(initCRDT),
      finished = Set.empty,
      globalProgress = Map.empty,
      window = LocalWin(0),
      msgRef = LocalWin(initMsgRef)
    )
given [A: CRDT, R]: CRDT[Wcrdt[A, R]] with
  extension (x: Wcrdt[A, R])
    def \/(y: Wcrdt[A, R]): Wcrdt[A, R] =
      Wcrdt(
        procID = x.procID \/ y.procID,
        innerCRDT = x.innerCRDT \/ y.innerCRDT,
        globalProgress = x.globalProgress \/ y.globalProgress,
        finished = x.finished \/ y.finished,
        window = x.window \/ y.window,
        msgRef = x.msgRef \/ y.msgRef
      )
