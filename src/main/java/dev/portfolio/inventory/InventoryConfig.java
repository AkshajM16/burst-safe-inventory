package dev.portfolio.inventory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryConfig {}
