package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.test.publisher.TestPublisher;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.PaymentMethod;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.web.api.EmailOrder.EmailTaco;

class EmailOrderServiceTest {

  private UserRepository userRepo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentRepo;
  private EmailOrderService service;
  private User user;
  private PaymentMethod payment;

  @BeforeEach
  void setUp() {
    userRepo = mock(UserRepository.class);
    ingredientRepo = mock(IngredientRepository.class);
    paymentRepo = mock(PaymentMethodRepository.class);
    service = new EmailOrderService(userRepo, ingredientRepo, paymentRepo);
    user = user("owner@example.test");
    payment = new PaymentMethod(user, "4111111111111111", "123", "12/99");
    payment.setId("PAYMENT-ID");
  }

  @Test
  void shouldConvertMultipleTacosWithAllIngredientsInOrder() {
    Ingredient wrap = new Ingredient("WRAP", "Wrap", Type.WRAP);
    Ingredient protein = new Ingredient("PROT", "Protein", Type.PROTEIN);
    Ingredient sauce = new Ingredient("SAUC", "Sauce", Type.SAUCE);
    PublisherProbe<User> userLookup = PublisherProbe.of(Mono.just(user));
    when(userRepo.findByEmail(user.getEmail())).thenReturn(userLookup.mono());
    when(paymentRepo.findByUserId(user.getId())).thenReturn(Mono.just(payment));
    when(ingredientRepo.findById("WRAP")).thenReturn(Mono.just(wrap));
    when(ingredientRepo.findById("PROT")).thenReturn(Mono.just(protein));
    when(ingredientRepo.findById("SAUC")).thenReturn(Mono.just(sauce));

    EmailOrder email = emailOrder(
        taco("First", "WRAP", "PROT"),
        taco("Second", "SAUC", "WRAP"));

    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.just(email)))
        .assertNext(order -> {
          assertSame(user, order.getUser());
          assertEquals("4111111111111111", order.getCcNumber());
          assertEquals("123", order.getCcCVV());
          assertEquals("12/99", order.getCcExpiration());
          assertEquals(Arrays.asList("First", "Second"), Arrays.asList(
              order.getTacos().get(0).getName(), order.getTacos().get(1).getName()));
          assertEquals(Arrays.asList(wrap, protein), order.getTacos().get(0).getIngredients());
          assertEquals(Arrays.asList(sauce, wrap), order.getTacos().get(1).getIngredients());
        })
        .verifyComplete();

    assertEquals(1, userLookup.subscribeCount());
    verify(paymentRepo).findByUserId(user.getId());
    InOrder ingredientOrder = inOrder(ingredientRepo);
    ingredientOrder.verify(ingredientRepo).findById("WRAP");
    ingredientOrder.verify(ingredientRepo).findById("PROT");
    ingredientOrder.verify(ingredientRepo).findById("SAUC");
    ingredientOrder.verify(ingredientRepo).findById("WRAP");
  }

  @Test
  void shouldFailWhenIngredientDoesNotExist() {
    stubUserAndPayment();
    when(ingredientRepo.findById("MISSING")).thenReturn(Mono.empty());

    StepVerifier.create(service.convertEmailOrderToDomainOrder(
        Mono.just(emailOrder(taco("Incomplete", "MISSING")))))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof EmailOrderIngredientNotFoundException);
          EmailOrderIngredientNotFoundException missing =
              (EmailOrderIngredientNotFoundException) error;
          assertEquals("MISSING", missing.getIngredientId());
          assertEquals("EMAIL_ORDER_INGREDIENT_NOT_FOUND", missing.getCode());
        })
        .verify();
  }

  @Test
  void shouldFailWhenUserDoesNotExist() {
    when(userRepo.findByEmail("owner@example.test")).thenReturn(Mono.empty());

    StepVerifier.create(service.convertEmailOrderToDomainOrder(
        Mono.just(emailOrder(taco("Taco", "WRAP")))))
        .expectError(EmailOrderUserNotFoundException.class)
        .verify();

    verifyNoInteractions(paymentRepo, ingredientRepo);
  }

  @Test
  void shouldFailWhenPaymentMethodDoesNotExist() {
    when(userRepo.findByEmail(user.getEmail())).thenReturn(Mono.just(user));
    when(paymentRepo.findByUserId(user.getId())).thenReturn(Mono.empty());

    StepVerifier.create(service.convertEmailOrderToDomainOrder(
        Mono.just(emailOrder(taco("Taco", "WRAP")))))
        .expectError(EmailOrderPaymentMethodNotFoundException.class)
        .verify();

    verifyNoInteractions(ingredientRepo);
  }

  @Test
  void shouldFailWithTypedErrorForEmptyEmailOrderPublisher() {
    StepVerifier.create(service.convertEmailOrderToDomainOrder(Mono.empty()))
        .expectErrorSatisfies(error -> {
          assertTrue(error instanceof InvalidEmailOrderException);
          assertEquals("INVALID_EMAIL_ORDER",
              ((InvalidEmailOrderException) error).getCode());
        })
        .verify();

    verifyNoInteractions(userRepo, paymentRepo, ingredientRepo);
  }

  @Test
  void shouldFailWithTypedErrorForNullIngredientCollection() {
    stubUserAndPayment();
    EmailTaco invalidTaco = new EmailTaco();
    invalidTaco.setName("Incomplete");
    invalidTaco.setIngredients(null);

    StepVerifier.create(service.convertEmailOrderToDomainOrder(
        Mono.just(emailOrder(invalidTaco))))
        .expectError(InvalidEmailOrderException.class)
        .verify();

    verifyNoInteractions(ingredientRepo);
  }

  @Test
  void shouldNotEmitOrderUntilIngredientLookupCompletes() {
    stubUserAndPayment();
    Ingredient wrap = new Ingredient("WAIT", "Waiting wrap", Type.WRAP);
    TestPublisher<Ingredient> ingredientPublisher = TestPublisher.createCold();
    when(ingredientRepo.findById("WAIT")).thenReturn(ingredientPublisher.mono());

    StepVerifier.create(service.convertEmailOrderToDomainOrder(
        Mono.just(emailOrder(taco("Patient taco", "WAIT")))))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(20))
        .then(() -> ingredientPublisher.emit(wrap))
        .assertNext(order -> assertEquals(Arrays.asList(wrap),
            order.getTacos().get(0).getIngredients()))
        .verifyComplete();
  }

  private void stubUserAndPayment() {
    when(userRepo.findByEmail(user.getEmail())).thenReturn(Mono.just(user));
    when(paymentRepo.findByUserId(user.getId())).thenReturn(Mono.just(payment));
  }

  private EmailOrder emailOrder(EmailTaco... tacos) {
    EmailOrder order = new EmailOrder();
    order.setEmail(user.getEmail());
    order.setTacos(Arrays.asList(tacos));
    return order;
  }

  private EmailTaco taco(String name, String... ingredientIds) {
    EmailTaco taco = new EmailTaco();
    taco.setName(name);
    taco.setIngredients(Arrays.asList(ingredientIds));
    return taco;
  }

  private User user(String email) {
    User result = new User("owner", "N/A", "Owner Name", "Street", "City", "ST",
        "00000", "0000000000", email);
    result.setId("USER-ID");
    return result;
  }
}
