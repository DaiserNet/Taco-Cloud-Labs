package tacos.web.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import lombok.Data;

@Data 
public class OrderPatchRequest {
    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*")
    private String deliveryName;

    @Size(max = 150)
    @Pattern(regexp = ".*\\S.*")
    private String deliveryStreet;

    @Size(max = 100)
    @Pattern(regexp = ".*\\S.*")
    private String deliveryCity;

    @Size(max = 50)
    @Pattern(regexp = ".*\\S.*")
    private String deliveryState;

    @Size(max = 20)
    @Pattern(regexp = ".*\\S.*")
    private String deliveryZip;

    @JsonAnySetter 
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException(
            "Field not allowed in order patch: " + name);
    }
}
