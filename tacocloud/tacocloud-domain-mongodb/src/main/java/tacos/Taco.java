package tacos;

import java.util.Date;
import java.util.List;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.rest.core.annotation.RestResource;

import lombok.Data;

@Data
@RestResource(rel = "tacos", path = "tacos")
@Document
@CompoundIndexes({
    @CompoundIndex(name = "taco_created_id",
        def = "{'createdAt': -1, '_id': -1}"),
    @CompoundIndex(name = "taco_name_id",
        def = "{'name': 1, '_id': 1}"),
    @CompoundIndex(name = "taco_ingredient_created_id",
        def = "{'ingredients._id': 1, 'createdAt': -1, '_id': -1}")
})
public class Taco {

  @Id
  private String id;
  
  @NotNull
  @Size(min = 5, message = "Name must be at least 5 characters long")
  private String name;
  
  private Date createdAt = new Date();
  
  @Size(min=1, message="You must choose at least 1 ingredient")
  private List<Ingredient> ingredients;

}
