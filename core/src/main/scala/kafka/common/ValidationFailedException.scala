package kafka.common

/**
  * Indicates the message is not valid
  * Added by xccui
  *
  */
class ValidationFailedException(message: String) extends RuntimeException(message) {
  def this() = this(null)
}
