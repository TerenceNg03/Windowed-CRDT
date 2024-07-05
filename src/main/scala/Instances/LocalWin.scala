package Instances

import Types.CRDT

case class LocalWin[A](
    val v: A
)

given [A]: CRDT[LocalWin[A]] with
  extension (x: LocalWin[A]) def \/(y: LocalWin[A]): LocalWin[A] = x
