package com.example.adoptions;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class AdoptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdoptionsApplication.class, args);
    }

    @Bean
    McpSyncClient mcpSyncClient() {
        var mcp = McpClient
                .sync(new HttpClientSseClientTransport("http://localhost:8081"))
                .build();
        mcp.initialize();
        return mcp;
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          McpSyncClient mcpSyncClient,
                          DogRepository repository,
                          VectorStore vectorStore) {

        if (false)
            repository.findAll().forEach(dog -> {
                var dogument = new Document("id: %s, name: %s, description: %s".formatted(
                        dog.id(), dog.name(), dog.description()
                ));
                vectorStore.add(List.of(dogument));
            });

        var system = """
                You are an AI powered assistant to help people adopt a dog from the adoption\s
                agency named Pooch Palace with locations in Austin, Seoul, Tokyo, Singapore, Paris,\s
                Mumbai, New Delhi, Barcelona, San Francisco, and London. Information about the dogs available\s
                will be presented below. If there is no information, then return a polite response suggesting we\s
                don't have any dogs available.
                """;

        return builder
                .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore))
                .defaultSystem(system)
                .defaultTools(new SyncMcpToolCallbackProvider(mcpSyncClient))
                .build();
    }

}


interface DogRepository extends ListCrudRepository<Dog, Integer> {
}

// look mom, no Lombok!!
record Dog(@Id int id, String name, String owner, String description) {
}


@Controller
@ResponseBody
class AdoptionsController {

    private final ChatClient ai;

    private final Map<String, PromptChatMemoryAdvisor> memory
            = new ConcurrentHashMap<>();

    AdoptionsController(ChatClient ai) {
        this.ai = ai;
    }

    @GetMapping("/{user}/inquire")
    String inquire(@PathVariable String user, @RequestParam String question) {

        var advisor = this.memory
                .computeIfAbsent(user, _ -> PromptChatMemoryAdvisor.builder(new InMemoryChatMemory())
                        .build());
        return this.ai
                .prompt()
                .user(question)
                .advisors(advisor)
                .call()
                .content();

    }

}