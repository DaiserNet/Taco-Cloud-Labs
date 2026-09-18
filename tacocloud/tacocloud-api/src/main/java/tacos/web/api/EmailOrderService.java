package tacos.web.api;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.TacoOrder;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.web.api.EmailOrder.EmailTaco;

@Service
public class EmailOrderService {

  private UserRepository userRepo;
  private IngredientRepository ingredientRepo;
  private PaymentMethodRepository paymentMethodRepo;

  public EmailOrderService(UserRepository userRepo, IngredientRepository ingredientRepo,
      PaymentMethodRepository paymentMethodRepo) {
    this.userRepo = userRepo;
    this.ingredientRepo = ingredientRepo;
    this.paymentMethodRepo = paymentMethodRepo;
  }

  public Mono<TacoOrder> convertEmailOrderToDomainOrder(Mono<EmailOrder> emailOrder) {
    if (emailOrder == null) {
      return Mono.error(new InvalidEmailOrderException(
          "Email order publisher is required"));
    }

    return emailOrder
        .switchIfEmpty(Mono.error(new InvalidEmailOrderException(
            "Email order is required")))
        .flatMap(this::convertOrder);
  }

  private Mono<TacoOrder> convertOrder(EmailOrder emailOrder) {
    if (!StringUtils.hasText(emailOrder.getEmail()) || emailOrder.getTacos() == null) {
      return Mono.error(new InvalidEmailOrderException(
          "Email and taco collection are required"));
    }

    return userRepo.findByEmail(emailOrder.getEmail())
        .switchIfEmpty(Mono.error(new EmailOrderUserNotFoundException()))
        .flatMap(user -> paymentMethodRepo.findByUserId(user.getId())
            .switchIfEmpty(Mono.error(new EmailOrderPaymentMethodNotFoundException()))
            .flatMap(paymentMethod -> convertTacos(emailOrder.getTacos())
                .map(tacos -> buildOrder(user, paymentMethod, tacos))));
  }

  private Mono<List<Taco>> convertTacos(List<EmailTaco> emailTacos) {
    return Flux.fromIterable(emailTacos)
        .concatMap(this::convertTaco)
        .collectList();
  }

  private Mono<Taco> convertTaco(EmailTaco emailTaco) {
    if (emailTaco == null || !StringUtils.hasText(emailTaco.getName())
        || emailTaco.getIngredients() == null) {
      return Mono.error(new InvalidEmailOrderException(
          "Each taco requires a name and ingredient collection"));
    }

    return Flux.fromIterable(emailTaco.getIngredients())
        .concatMap(ingredientId -> {
          if (!StringUtils.hasText(ingredientId)) {
            return Mono.<Ingredient>error(new InvalidEmailOrderException(
                "Ingredient identifiers must contain text"));
          }
          return ingredientRepo.findById(ingredientId)
              .switchIfEmpty(Mono.error(
                  new EmailOrderIngredientNotFoundException(ingredientId)));
        })
        .collectList()
        .map(ingredients -> {
          Taco taco = new Taco();
          taco.setName(emailTaco.getName());
          taco.setIngredients(ingredients);
          return taco;
        });
  }

  private TacoOrder buildOrder(User user, PaymentMethod paymentMethod, List<Taco> tacos) {
    TacoOrder order = new TacoOrder();
    order.setUser(user);
    order.setCcNumber(paymentMethod.getCcNumber());
    order.setCcCVV(paymentMethod.getCcCVV());
    order.setCcExpiration(paymentMethod.getCcExpiration());
    order.setDeliveryName(user.getFullname());
    order.setDeliveryStreet(user.getStreet());
    order.setDeliveryCity(user.getCity());
    order.setDeliveryState(user.getState());
    order.setDeliveryZip(user.getZip());
    order.setPlacedAt(new Date());
    order.setTacos(tacos);
    return order;
  }

}
