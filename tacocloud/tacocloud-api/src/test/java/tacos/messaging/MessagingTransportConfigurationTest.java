package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.core.KafkaTemplate;

class MessagingTransportConfigurationTest {
  private final JmsTemplate jms = mock(JmsTemplate.class);
  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, OrderEvent> kafka = mock(KafkaTemplate.class);

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(MessagingTransportGuard.class,
            NoOpOrderMessagingService.class,
            JmsOrderMessagingService.class, JmsMessagingConfig.class,
            RabbitOrderMessagingService.class, RabbitMessagingConfig.class,
            KafkaOrderMessagingService.class)
        .withBean(JmsTemplate.class, () -> jms)
        .withBean(RabbitTemplate.class, () -> rabbit)
        .withBean(KafkaTemplate.class, () -> kafka)
        .withPropertyValues(
            "tacocloud.messaging.jms.destination=jms.orders",
            "tacocloud.messaging.rabbitmq.destination=rabbit.orders",
            "tacocloud.messaging.kafka.topic=kafka.orders");
  }

  @Test
  void shouldUseNoopOnlyWhenTransportIsAbsentOrExplicit() {
    runner().run(context -> assertOnly(context, NoOpOrderMessagingService.class));
    runner().withPropertyValues("tacocloud.messaging.transport=noop")
        .run(context -> assertOnly(context, NoOpOrderMessagingService.class));
  }

  @Test
  void shouldSelectExactlyOneJmsAdapterWithoutConnecting() {
    runner().withPropertyValues("tacocloud.messaging.transport=jms")
        .run(context -> {
          assertOnly(context, JmsOrderMessagingService.class);
          assertThat(context).hasSingleBean(
              org.springframework.jms.support.converter.MappingJackson2MessageConverter.class);
          context.getBean(OrderMessagingService.class).sendOrder(event());
          verify(jms).convertAndSend(eq("jms.orders"), any(OrderEvent.class),
              any(org.springframework.jms.core.MessagePostProcessor.class));
        });
  }

  @Test
  void shouldSelectExactlyOneRabbitAdapterAndExternalDestination() {
    runner().withPropertyValues("tacocloud.messaging.transport=rabbitmq")
        .run(context -> {
          assertOnly(context, RabbitOrderMessagingService.class);
          assertThat(context).hasSingleBean(
              org.springframework.amqp.support.converter.Jackson2JsonMessageConverter.class);
          context.getBean(OrderMessagingService.class).sendOrder(event());
          verify(rabbit).convertAndSend(eq("rabbit.orders"), any(OrderEvent.class),
              any(MessagePostProcessor.class));
        });
  }

  @Test
  void shouldSelectExactlyOneKafkaAdapterAndExternalTopic() {
    runner().withPropertyValues("tacocloud.messaging.transport=kafka")
        .run(context -> {
          assertOnly(context, KafkaOrderMessagingService.class);
          OrderEvent event = event();
          context.getBean(OrderMessagingService.class).sendOrder(event);
          verify(kafka).send("kafka.orders", event);
        });
  }

  @Test
  void shouldRejectUnknownTransportWithClearMessage() {
    runner().withPropertyValues("tacocloud.messaging.transport=unknown")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining(
              "Unknown tacocloud.messaging.transport='unknown'");
        });
  }

  @Test
  void shouldRejectTwoPortBeansAtStartup() {
    runner().withBean("extraAdapter", OrderMessagingService.class,
        () -> event -> { })
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining(
              "Expected exactly one OrderMessagingService");
        });
  }

  @Test
  void shouldRejectNoopAndMissingBrokerCredentialsInProduction() {
    runner().withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining(
              "Production requires an explicit broker transport");
        });
    runner().withPropertyValues("spring.profiles.active=prod",
        "tacocloud.messaging.transport=jms")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining(
              "Production messaging requires spring.artemis.user");
        });
    runner().withPropertyValues("spring.profiles.active=prod",
        "tacocloud.messaging.transport=rabbitmq")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining(
              "Production messaging requires spring.rabbitmq.username");
        });
  }

  @Test
  void shouldNotDefaultToNoopForOtherDeploymentProfiles() {
    runner().withPropertyValues("spring.profiles.active=staging")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining(
              "Noop messaging is only allowed for dev/test");
        });
  }

  private void assertOnly(org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
      Class<? extends OrderMessagingService> type) {
    assertThat(context).hasNotFailed();
    Map<String, OrderMessagingService> beans = context.getBeansOfType(OrderMessagingService.class);
    assertThat(beans).hasSize(1);
    assertThat(beans.values().iterator().next()).isInstanceOf(type);
  }

  private OrderEvent event() {
    return OrderEvent.created("ORDER-1", new OrderEventPayload("ORDER-1",
        Collections.emptyList()));
  }
}
