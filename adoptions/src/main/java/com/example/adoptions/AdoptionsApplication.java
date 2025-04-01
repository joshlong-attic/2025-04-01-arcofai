package com.example.adoptions;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class AdoptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdoptionsApplication.class, args);
    }

    @Bean
    ChatClient chatClient(
            ChatClient.Builder builder,
            JdbcClient db,
            DogAdoptionScheduler scheduler ,
            DogRepository repository,
            VectorStore vectorStore) {

        if (db.sql(" select count( id ) from vector_store ").query(Integer.class).single().equals(0)) {
            repository.findAll().forEach(dog -> {
                var dogument = new Document("id: %s, name: %s, description: %s"
                        .formatted(dog.id(), dog.name(), dog.description()));
                vectorStore.add(List.of(dogument));
            });
        }

        var system = """
                You are an AI powered assistant to help people adopt a dog from the adoption\s
                agency named Pooch Palace with locations in Antwerp, Seoul, Tokyo, Singapore, Paris,\s
                Mumbai, New Delhi, Barcelona, San Francisco, and London. Information about the dogs available\s
                will be presented below. If there is no information, then return a polite response suggesting we\s
                don't have any dogs available.
                """;
        return builder
                .defaultSystem(system)
                .defaultTools(scheduler)
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore))
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
    String scheduleAnAppointment(@ToolParam(description = "the id of the dog") int dogId,
                                 @ToolParam(description = "the name of the dog") String dogName) {
        var when = Instant
                .now()
                .plus(3, ChronoUnit.DAYS)
                .toString();
        System.out.println("scheduled an appointment for " +dogId+" named "+ dogName + " at " + when);
        return when;
    }

}

@ResponseBody
@Controller
class AdoptionsController {

    private final Map<String, PromptChatMemoryAdvisor> advisors = new ConcurrentHashMap<>();

    private final ChatClient ai;

    AdoptionsController(ChatClient ai) {
        this.ai = ai;
    }

    @GetMapping("/{user}/inquire")
    String inquire(@PathVariable String user, @RequestParam String question) {
        var advisor = this.advisors
                .computeIfAbsent(user, u -> PromptChatMemoryAdvisor
                        .builder(new InMemoryChatMemory())
                        .build());
        return this.ai
                .prompt()
                .advisors(advisor)
                .user(question)
                .call()
                .content();

    }

    private String route(String query) {
        return "";
    }

}
