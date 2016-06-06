package kafka.validator

import kafka.common.{TopicAndPartition}
import kafka.message.MessageSet

/**
  * The default message validator do nothing.
  */
class DefaultMessageValidator extends MessageValidator {
  override def validate(topicAndPartition: TopicAndPartition, messageSet: MessageSet): Unit = {
    //do nothing
  }
}
