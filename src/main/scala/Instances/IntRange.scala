package Instances

import Types.PersistStream

case class IntRange(
    val next: Int,
    val end: Int
)

given [A]:PersistStream[IntRange, Int] with
    extension (x: IntRange) def next: (Option[Int], IntRange) = 
        if x.next >= x.end then
            (None, x)
        else
            (Some(x.next), IntRange(x.next + 1, x.end))

    def empty = IntRange(0,0)