package dev.tauri.choam
package stream

import cats.effect.std.{ Queue => CatsQueue }

import async.{ AsyncReactive, AsyncQueueSource, BoundedQueueSink }

private final class Fs2QueueWrapper[F[_], A](
  self: AsyncQueueSource[A] with BoundedQueueSink[A],
)(implicit F: AsyncReactive[F]) extends CatsQueue[F, A] {
  final override def take: F[A] =
    self.deque
  final override def tryTake: F[Option[A]] =
    self.tryDeque.run[F]
  final override def size: F[Int] =
    F.asyncInst.raiseError(new AssertionError) // FS2 doesn't really need `size`, so we cheat
  final override def offer(a: A): F[Unit] =
    self.enqueue[F](a)
  final override def tryOffer(a: A): F[Boolean] =
    F.apply(self.tryEnqueue, a)
}
