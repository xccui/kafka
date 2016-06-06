package kafka.validator

import kafka.common.TopicAndPartition
import kafka.message.MessageSet

/**
  * The message validator trait is used to validate messages that come from producer clients.
  * When encountered an invalid message, it should throw a ValidationFailed exception.
  * Then if the producer client needs ack, it will get a response with the exception.
  */
trait MessageValidator {
  def validate(topicAndPartition: TopicAndPartition, messageSet: MessageSet)
}
