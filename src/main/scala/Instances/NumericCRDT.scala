package Instances

import java.util.UUID
import Types.CRDT
import Types.Lattice
import scala.math.Numeric.Implicits.infixNumericOps
import scala.math.Ordering.Implicits.infixOrderingOps

class NumericCRDT[A: Numeric](
    val uuid: UUID,
    val incM: Map[UUID, A],
    val decM: Map[UUID, A]
):
  override def toString(): String = s"id: $uuid\nincM: $incM\ndecM: $decM\n"
  def modify(x: A): NumericCRDT[A] =
    val zero = summon[Numeric[A]].zero
    val incM_ =
      if x > zero then
        incM + incM
          .get(uuid)
          .map(summon[Numeric[A]].plus.curried(x))
          .map(uuid -> _)
          .getOrElse(uuid -> x)
      else incM
    val decM_ =
      if x < zero then
        decM + decM
          .get(uuid)
          .map(summon[Numeric[A]].plus.curried(x))
          .map(uuid -> _)
          .getOrElse(uuid -> -x)
      else decM
    NumericCRDT(
      uuid,
      incM_,
      decM_
    )

given [A: Numeric]: Lattice[NumericCRDT[A]] with
  extension (x: NumericCRDT[A])
    def \/(y: NumericCRDT[A]): NumericCRDT[A] =
      val zero = summon[Numeric[A]].zero
      val keys = (a: Map[UUID, A]) =>
        (b: Map[UUID, A]) => a.keySet.union(b.keySet)
      val inc_ = Map.from(
        keys(x.incM)(y.incM).map(k =>
          (k, (x.incM.getOrElse(k, zero) max y.incM.getOrElse(k, zero)))
        )
      )
      val dec_ = Map.from(
        keys(x.decM)(y.decM).map(k =>
          (k, (x.decM.getOrElse(k, zero) max y.decM.getOrElse(k, zero)))
        )
      )
      NumericCRDT(x.uuid, inc_, dec_)

  extension (x: NumericCRDT[A])
    def /\(y: NumericCRDT[A]): NumericCRDT[A] =
      val zero = summon[Numeric[A]].zero
      val keys = (a: Map[UUID, A]) =>
        (b: Map[UUID, A]) => a.keySet.intersect(b.keySet)
      val inc_ = Map.from(
        keys(x.incM)(y.incM).map(k =>
          (k, (x.incM.getOrElse(k, zero) min y.incM.getOrElse(k, zero)))
        )
      )
      val dec_ = Map.from(
        keys(x.decM)(y.decM).map(k =>
          (k, (x.decM.getOrElse(k, zero) min y.decM.getOrElse(k, zero)))
        )
      )
      NumericCRDT(x.uuid, inc_, dec_)

given [A: Numeric](using x: Lattice[NumericCRDT[A]]): CRDT[NumericCRDT[A], A]
with
  extension (x: NumericCRDT[A])
    def run(): A =
      val zero = summon[Numeric[A]].zero
      val plus = summon[Numeric[A]].plus
      x.incM.values.fold(zero)(plus) - x.decM.values.fold(zero)(plus)

  def pure(x: A) =
    val uuid = UUID.randomUUID()
    val zero = summon[Numeric[A]].zero
    if x >= zero then NumericCRDT(uuid, Map.from((uuid, x) :: Nil), Map.empty)
    else NumericCRDT(uuid, Map.empty, Map.from((uuid, -x) :: Nil))

  extension (x: NumericCRDT[A])
    def fork(): NumericCRDT[A] =
      NumericCRDT(UUID.randomUUID(), x.incM, x.decM)

given [A: Numeric]: Ordering[NumericCRDT[A]] with
  def compare(x: NumericCRDT[A], y: NumericCRDT[A]): Int =
    summon[Numeric[A]].compare(x.run(), y.run())
