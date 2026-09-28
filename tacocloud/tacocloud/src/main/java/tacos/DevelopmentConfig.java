package tacos;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.data.OrderRepository;

@Profile("!prod")
@Configuration
public class DevelopmentConfig {

  @Bean
  public CommandLineRunner dataLoader(IngredientRepository repo,
        UserRepository userRepo, PasswordEncoder encoder, TacoRepository tacoRepo,
        OrderRepository orderRepo, PaymentMethodRepository paymentMethodRepo) { // user repo for ease of testing with a built-in user
    
    return new CommandLineRunner() {
      @Override
      public void run(String... args) throws Exception {
        Ingredient flourTortilla = saveAnIngredient(
            "FLTO", "Flour Tortilla", Type.WRAP, "0.50", 100, 20,
            tags(DietaryTag.VEGAN, DietaryTag.VEGETARIAN),
            allergens(Allergen.GLUTEN), SpiceLevel.NONE);
        Ingredient cornTortilla = saveAnIngredient(
            "COTO", "Corn Tortilla", Type.WRAP, "0.45", 100, 20,
            tags(DietaryTag.VEGAN, DietaryTag.VEGETARIAN,
                DietaryTag.GLUTEN_FREE), allergens(), SpiceLevel.NONE);
        Ingredient groundBeef = saveAnIngredient(
            "GRBF", "Ground Beef", Type.PROTEIN, "1.25", 80, 15,
            tags(DietaryTag.GLUTEN_FREE), allergens(), SpiceLevel.NONE);
        Ingredient carnitas = saveAnIngredient(
            "CARN", "Carnitas", Type.PROTEIN, "1.35", 80, 15,
            tags(DietaryTag.GLUTEN_FREE), allergens(), SpiceLevel.NONE);
        Ingredient tomatoes = saveAnIngredient(
            "TMTO", "Diced Tomatoes", Type.VEGGIES, "0.35", 120, 25,
            tags(DietaryTag.VEGAN, DietaryTag.VEGETARIAN,
                DietaryTag.GLUTEN_FREE), allergens(), SpiceLevel.NONE);
        Ingredient lettuce = saveAnIngredient(
            "LETC", "Lettuce", Type.VEGGIES, "0.30", 120, 25,
            tags(DietaryTag.VEGAN, DietaryTag.VEGETARIAN,
                DietaryTag.GLUTEN_FREE), allergens(), SpiceLevel.NONE);
        Ingredient cheddar = saveAnIngredient(
            "CHED", "Cheddar", Type.CHEESE, "0.60", 90, 20,
            tags(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            allergens(Allergen.MILK), SpiceLevel.NONE);
        Ingredient jack = saveAnIngredient(
            "JACK", "Monterrey Jack", Type.CHEESE, "0.65", 90, 20,
            tags(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            allergens(Allergen.MILK), SpiceLevel.NONE);
        Ingredient salsa = saveAnIngredient(
            "SLSA", "Salsa", Type.SAUCE, "0.40", 100, 20,
            tags(DietaryTag.VEGAN, DietaryTag.VEGETARIAN,
                DietaryTag.GLUTEN_FREE), allergens(), SpiceLevel.MEDIUM);
        Ingredient sourCream = saveAnIngredient(
            "SRCR", "Sour Cream", Type.SAUCE, "0.50", 100, 20,
            tags(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE),
            allergens(Allergen.MILK), SpiceLevel.NONE);
        
//        UserUDT u = new UserUDT(username, fullname, phoneNumber)
        
        userRepo.save(new User("habuma", encoder.encode("password"), 
              "Craig Walls", "123 North Street", "Cross Roads", "TX", 
              "76227", "123-123-1234", "craig@habuma.com"))
          .subscribe(user -> {
              TacoOrder order = new TacoOrder();

              order.setId("ORDER1");
              order.setUser(user);
              order.setDeliveryName("Craig Walls");
              order.setDeliveryStreet("123 North Street");
              order.setDeliveryCity("Cross Roads");
              order.setDeliveryState("TX");
              order.setDeliveryZip("76227");
              orderRepo.save(order).subscribe();
              paymentMethodRepo.save(new PaymentMethod(user,
                  "tok_fake_development_habuma", "VISA", "1111", "10/30"))
                  .subscribe();
          });       
          
        
        // Pruebas para TC-04
        
        userRepo.save(new User("testuser", encoder.encode("password"), 
              "Test User", "456 South Street", "Somewhere", "CA", 
              "90210", "987-654-3210", "testuser@test.com"))
          .subscribe(user -> {
              paymentMethodRepo.save(new PaymentMethod(user,
                  "tok_fake_development_testuser", "MASTERCARD", "5555", "09/30"))
                  .subscribe();
          });

        Taco taco1 = new Taco();
        taco1.setId("TACO1");
        taco1.setName("Carnivore");
        taco1.setIngredients(Arrays.asList(flourTortilla, groundBeef, carnitas, sourCream, salsa, cheddar));
        tacoRepo.save(taco1).subscribe();

        Taco taco2 = new Taco();
        taco2.setId("TACO2");
        taco2.setName("Bovine Bounty");
        taco2.setIngredients(Arrays.asList(cornTortilla, groundBeef, cheddar, jack, sourCream));
        tacoRepo.save(taco2).subscribe();

        Taco taco3 = new Taco();
        taco3.setId("TACO3");
        taco3.setName("Veg-Out");
        taco3.setIngredients(Arrays.asList(flourTortilla, cornTortilla, tomatoes, lettuce, salsa));
        tacoRepo.save(taco3).subscribe();

      }

      private Ingredient saveAnIngredient(String id, String name, Type type,
          String unitPrice, int stockOnHand, int reorderLevel,
          Set<DietaryTag> dietaryTags, Set<Allergen> allergens,
          SpiceLevel spiceLevel) {
        Ingredient ingredient = new Ingredient(id, name, type,
            new BigDecimal(unitPrice), true, stockOnHand, reorderLevel);
        ingredient.setDietaryTags(dietaryTags);
        ingredient.setAllergens(allergens);
        ingredient.setSpiceLevel(spiceLevel);
        repo.save(ingredient).subscribe();
        return ingredient;
      }

      private Set<DietaryTag> tags(DietaryTag first, DietaryTag... rest) {
        Set<DietaryTag> tags = EnumSet.of(first);
        tags.addAll(Arrays.asList(rest));
        return tags;
      }

      private Set<Allergen> allergens(Allergen... values) {
        Set<Allergen> allergens = EnumSet.noneOf(Allergen.class);
        allergens.addAll(Arrays.asList(values));
        return allergens;
      }
    };
  }
  
}
