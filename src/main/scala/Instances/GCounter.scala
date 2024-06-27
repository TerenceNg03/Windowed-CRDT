package Instances

import java.util.UUID
import Types.CRDT
import scala.math.Numeric.Implicits.infixNumericOps
import scala.math.Ordering.Implicits.infixOrderingOps

class GCounter[A, C](
    val procID: C,
    val incM: Map[C, A]
):
  def increase(x:A)(using a: Numeric[A]):GCounter[A, C] = 
    assert(x >= summon[Numeric[A]].zero)
    GCounter(procID, incM.updated(procID, incM(procID)+x))

given [A: Numeric, C]: CRDT[GCounter[A, C], A, C] with
  def newCRDT(x: A)(procID: C): GCounter[A, C] =
    assert(x >= summon[Numeric[A]].zero)
    GCounter(procID, Map((procID -> x)))

  extension (x: GCounter[A, C])
    def \/(y: GCounter[A, C]): GCounter[A, C] =
      val zero = summon[Numeric[A]].zero
      val keys = (a: Map[C, A]) => (b: Map[C, A]) => a.keySet.union(b.keySet)
      val inc_ = Map.from(
        keys(x.incM)(y.incM).map(k =>
          (k, (x.incM.getOrElse(k, zero) max y.incM.getOrElse(k, zero)))
        )
      )
      GCounter(x.procID, inc_)

  extension (x: GCounter[A, C])
    def read(): A =
      val zero = summon[Numeric[A]].zero
      val plus = summon[Numeric[A]].plus
      x.incM.values.fold(zero)(plus)
