package kafka.validator
import kafka.common.TopicAndPartition
import kafka.message.MessageSet

/**
  * ShowMessageLengthValidator
  *
  * @author xccui
  */
class ShowMessageLengthValidator extends MessageValidator{
  override def validate(topicAndPartition: TopicAndPartition, messageSet: MessageSet): Unit = {
    messageSet.foreach((ms) => {
      printf("Topic: %s\tPartition: %d\tLength: %d\n", topicAndPartition.topic, topicAndPartition.partition, ms.message.buffer.array().length)
    })
  }
}
