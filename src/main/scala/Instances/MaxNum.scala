package Instances

import Types.CRDT

case class MaxNum[A](
    val v: A
)

given [A: Numeric]:CRDT[MaxNum[A]] with
    extension (x: MaxNum[A]) def \/(y: MaxNum[A]): MaxNum[A] =
        MaxNum(summon[Numeric[A]].max(x.v, y.v))