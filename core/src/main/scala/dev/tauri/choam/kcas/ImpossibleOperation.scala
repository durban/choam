package dev.tauri.choam
package kcas

final class ImpossibleOperation(msg: String)
  extends IllegalArgumentException(msg) {

  final override def fillInStackTrace(): Throwable =
    this
}
