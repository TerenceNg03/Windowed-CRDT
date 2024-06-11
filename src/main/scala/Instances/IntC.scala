package Instances

import java.util.UUID
import Types.CRDT
import Types.Lattice
import scala.math.Numeric.Implicits.infixNumericOps

class NumericCRDT[A](
    val uuid: UUID,
    val inc: Map[UUID, A],
    val dec: Map[UUID, A]
)

given [A: Numeric]: Lattice[NumericCRDT[A]] with
  def \/(x: NumericCRDT[A])(y: NumericCRDT[A]): NumericCRDT[A] =
    val zero = summon[Numeric[A]].zero
    val keys = (a: Map[UUID, A]) =>
      (b: Map[UUID, A]) => a.keySet.union(b.keySet)
    val inc_ = Map.from(
      keys(x.inc)(y.inc).map(k =>
        (k, (x.inc.getOrElse(k, zero) + y.inc.getOrElse(k, zero)))
      )
    )
    val dec_ = Map.from(
      keys(x.dec)(y.dec).map(k =>
        (k, (x.dec.getOrElse(k, zero) + y.dec.getOrElse(k, zero)))
      )
    )
    NumericCRDT(x.uuid, inc_, dec_)

  def /\(x: NumericCRDT[A])(y: NumericCRDT[A]): NumericCRDT[A] =
    val zero = summon[Numeric[A]].zero
    val keys = (a: Map[UUID, A]) =>
      (b: Map[UUID, A]) => a.keySet.intersect(b.keySet)
    val inc_ = Map.from(
      keys(x.inc)(y.inc).map(k =>
        (k, (x.inc.getOrElse(k, zero) + y.inc.getOrElse(k, zero)))
      )
    )
    val dec_ = Map.from(
      keys(x.dec)(y.dec).map(k =>
        (k, (x.dec.getOrElse(k, zero) + y.dec.getOrElse(k, zero)))
      )
    )
    NumericCRDT(x.uuid, inc_, dec_)

given [A : Numeric & Lattice[NumericCRDT[A]]]: CRDT[NumericCRDT[A], A] with
    def runCRDT(x: NumericCRDT[A]): A =
        val zero = summon[Numeric[A]].zero
        val plus = summon[Numeric[A]].plus
        x.inc.values.fold(zero)(plus) - x.dec.values.fold(zero)(plus)

    val pure: A => NumericCRDT[A] = x =>
        val uuid = UUID.randomUUID()
        NumericCRDT(uuid, Map.from((uuid, x) :: Nil), Map.empty)

    def fork(x: NumericCRDT[A]): NumericCRDT[A] =
        NumericCRDT(UUID.randomUUID(), x.inc, x.dec)
