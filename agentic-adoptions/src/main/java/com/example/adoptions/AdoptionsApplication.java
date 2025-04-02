package com.example.adoptions;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Description;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class AdoptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdoptionsApplication.class, args);
    }

    @Bean
    ApplicationRunner vectorStoreInitialization(
            JdbcClient db,
            VectorStore vectorStore,
            DogRepository repository) {
        return args -> {

            if (db.sql(" select count( id ) from vector_store ").query(Integer.class).single().equals(0)) {
                repository.findAll().forEach(dog -> {
                    var dogument = new Document("id: %s, name: %s, description: %s"
                            .formatted(dog.id(), dog.name(), dog.description()));
                    vectorStore.add(List.of(dogument));
                });
            }
        };
    }

    @Description("""
                This chat client can handle all the other requests.
            """)
    @Bean
    ChatClient other(ChatClient.Builder builder) {
        var system = """
                     This is the last ditch. Requests should ideally not reach this point. If the request has something to do with dogs, 
                     feel free to provide a response. Otherwise, return an empty String (""), and nothing else. 
                """;
        return builder
                .defaultSystem(system)
                .build();
    }

    @Description("""
                This chat client can be used to handle all questions associated with finding dogs available for adoptions.
            """)
    @Bean
    ChatClient adoption(QuestionAnswerAdvisor advisor, ChatClient.Builder builder, VectorStore vectorStore) {
        var system = """
                Information about the dogs available will be presented below. 
                If there is no information, then return a polite response suggesting we don't have any dogs available.
                """;
        return builder
                .defaultAdvisors(advisor)
                .defaultSystem(system)
                .build();
    }

    @Bean
    QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        return new QuestionAnswerAdvisor(vectorStore);
    }

    @Bean
    @Description("""
                This chat client can be used to handle all work with a given user to follow up on adoptions. 
                This might include a range of activities like scheduling training, therapy, dog obedience training, etc.         
            """)
    ChatClient postAdoptionFollowup(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    @Description("""
            This chat client should be used to handle scheduling adoptions. It has access to the scheduling subsystem via tools. 
            This tool should be used to determine when a dog might be picked up or adopted from a given location.
            """)
    ChatClient adoptionScheduler(QuestionAnswerAdvisor advisor, ChatClient.Builder builder, DogAdoptionScheduler scheduler) {
        return builder
                .defaultTools(scheduler)
                .defaultAdvisors(advisor)
                .build();
    }
}

interface DogRepository extends ListCrudRepository<Dog, Integer> {
}

record Dog(@Id int id, String name, String owner, String description) {
}

@Component
class DogAdoptionScheduler {

    @Tool(description = "Schedule an appointment to pick up or adopt a dog")
    String scheduleAppointmentToAdoptADog(@ToolParam(description = "the id of the dog") int dogId,
                                          @ToolParam(description = "the name of the dog") String dogName) {
        var when = Instant
                .now()
                .plus(3, ChronoUnit.DAYS)
                .toString();
        System.out.println("scheduled an appointment for " + dogId + " named " + dogName + " at " + when);
        return when;
    }

}

@ResponseBody
@Controller
class AdoptionsController {

    private final Map<String, PromptChatMemoryAdvisor> advisors = new ConcurrentHashMap<>();
    private final ChatClient router;
    private final Map<String, ChatClient> routes;

    AdoptionsController(ChatClient.Builder builder, DefaultListableBeanFactory beans,
                        Map<String, ChatClient> routes) {
        var system = """
               You are an assistant to help people adopt adopt a dog from the adoption 
               agency named "Pooch Palace," with locations in Antwerp, Seoul, Tokyo, Singapore, Paris, 
               Mumbai, New Delhi, Barcelona, San Francisco, and London.
                """ + System.lineSeparator() + System.lineSeparator();
        this.routes = routes;

        var categories = new HashMap<String, String>();
        routes.forEach((cat, cc) -> {
            categories.put(cat,  system + beans.getBeanDefinition(cat).getDescription());
        });

        var menu = new StringBuffer();
        categories.forEach((category, description) -> {
            menu.append(category).append(" - ").append(description)
                    .append(System.lineSeparator()).append(System.lineSeparator());
        });




        var router = system + System.lineSeparator() + System.lineSeparator() +
                """
                   You are also a router. When a request comes in, inspect the request and determine 
                   which of the following categories best matches the nature of the request and then return 
                   the category, and only the category, of the best match. Here is a description of each
                   category and an explanation of why you might choose that category.
        """ + menu;
        this.router = builder
                .defaultSystem(router)
                .build();
    }


    @GetMapping("/{user}/inquire")
    String inquire(@PathVariable String user, @RequestParam String question) {
        var advisor = this.advisors
                .computeIfAbsent(user, u -> PromptChatMemoryAdvisor
                        .builder(new InMemoryChatMemory())
                        .build());
        var category = this.router
                .prompt()
                .advisors(advisor)
                .user(question)
                .call()
                .content();

        Assert.state(this.routes.containsKey(category), "the query does not match any category");
        System.out.println(category + " matches!");
        return this.routes
                .get(category)
                .prompt()
                .user(question)
                .advisors(advisor)
                .call()
                .content();

    }


}
