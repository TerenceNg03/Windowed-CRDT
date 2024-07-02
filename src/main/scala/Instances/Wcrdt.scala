package Instances

import Types.CRDT
import Instances.given

case class Wcrdt[A, C](
    val procID: C,
    val local: A,
    val l: GSet[(Int, C)],
    val w: GMap[Int, A],
    val window: Int
):
  def nextWindow[B]()(using x: CRDT[A, B, C]): Wcrdt[A, C] =
    val w_ = w get (window) match
      case Some(a) => a \/ local
      case None    => local
    Wcrdt(
      procID,
      local,
      l + (window -> procID),
      w updated (window, w_),
      window + 1
    )
  def query(w: Int)(procList: IterableOnce[C]): Option[A] =
    val ok = procList map (proc => (w, proc)) forall (x => l contains x)
    if ok then Some(this.w(w)) else None

  def update(f: A => A) = this.copy(local = f(local))
given [A, B, C](using x: CRDT[A, B, C]): CRDT[Wcrdt[A, C], A, C] with
  def bottom(procID: C): Wcrdt[A, C] = Wcrdt(
    procID,
    summon[CRDT[A, ?, ?]].bottom(procID),
    summon[CRDT[GSet[(Int, C)], ?, ?]].bottom(procID),
    summon[CRDT[GMap[Int, A], ?, ?]].bottom(procID),
    0
  )

  extension (x: Wcrdt[A, C])
    def \/(y: Wcrdt[A, C]): Wcrdt[A, C] =
      Wcrdt(x.procID, x.local, x.l \/ y.l, x.w \/ y.w, x.window)

  extension (x: Wcrdt[A, C]) def read(): A = x.local
