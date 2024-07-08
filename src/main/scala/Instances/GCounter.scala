package Instances

import Types.CRDT

import scala.math.Numeric.Implicits.infixNumericOps
import scala.math.Ordering.Implicits.infixOrderingOps

case class GCounter[A, C](
    val procId: C,
    val incM: Map[C, A]
):
  def increase(x: A)(using a: Numeric[A]): GCounter[A, C] =
    assert(x >= summon[Numeric[A]].zero)
    GCounter(procId, incM.updated(procId, incM(procId) + x))

  def value(using a: Numeric[A]): A =
    val zero = summon[Numeric[A]].zero
    val plus = summon[Numeric[A]].plus
    incM.values.fold(zero)(plus)

object GCounter:
  def newGCounter[A: Numeric, C](x: A)(procId: C): GCounter[A, C] =
    assert(x >= summon[Numeric[A]].zero)
    GCounter(procId, Map((procId -> x)))

given [A: Numeric, C]: CRDT[GCounter[A, C]] with
  extension (x: GCounter[A, C])
    def \/(y: GCounter[A, C]): GCounter[A, C] =
      val zero = summon[Numeric[A]].zero
      val keys = (a: Map[C, A]) => (b: Map[C, A]) => a.keySet.union(b.keySet)
      val inc_ = Map.from(
        keys(x.incM)(y.incM).map(k =>
          (k, (x.incM.getOrElse(k, zero) max y.incM.getOrElse(k, zero)))
        )
      )
      GCounter(x.procId, inc_)
