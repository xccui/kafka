package kafka.validator

import kafka.common.TopicAndPartition
import kafka.message.MessageSet

/**
  * ShowMessageKeyValidator
  *
  * @author xccui
  **/
class ShowMessageKeyValidator extends MessageValidator {
  override def validate(topicAndPartition: TopicAndPartition, messageSet: MessageSet): Unit = {
    messageSet.foreach((ms) => {
      val keyArray = new Array[Byte](ms.message.keySize)
      ms.message.key.get(keyArray)
      printf("Topic: %s\tPartition: %d\tKey: %s\n", topicAndPartition.topic, topicAndPartition.partition, new String(keyArray))
    })
  }
}
