package com.petcare;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class PetJsonConfig {
    @Bean public Jackson2ObjectMapperBuilderCustomizer petLongIds() {
        JsonSerializer<Long> serializer=new JsonSerializer<Long>() {
            @Override public void serialize(Long value,JsonGenerator output,SerializerProvider provider) throws IOException {
                if(value>9007199254740991L||value < -9007199254740991L) output.writeString(value.toString());
                else output.writeNumber(value);
            }
        };
        return builder->builder.serializerByType(Long.class,serializer).serializerByType(Long.TYPE,serializer);
    }
}
