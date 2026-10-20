package com.example.walletxfinance;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.hibernate.boot.model.naming.*;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
@TestConfiguration(proxyBeanMethods=false)
public class FinanceSchemaTestSupport {
 @Bean PhysicalNamingStrategy testPhysicalNamingStrategy(@Value("${test.finance-schema}") String schema) {
  return new PhysicalNamingStrategySnakeCaseImpl() {
   @Override public Identifier toPhysicalSchemaName(Identifier name,JdbcEnvironment environment) {
    return name!=null && "finance".equals(name.getText())?Identifier.toIdentifier(schema):super.toPhysicalSchemaName(name,environment);
   }
  };
 }
 public static void cleanup(javax.sql.DataSource dataSource,String schema) {
  if(!schema.matches("finance_test_[0-9a-f]{32}")) throw new IllegalArgumentException("Unexpected test schema");
  org.flywaydb.core.Flyway.configure().dataSource(dataSource).defaultSchema(schema).schemas(schema).cleanDisabled(false).load().clean();
 }
}
