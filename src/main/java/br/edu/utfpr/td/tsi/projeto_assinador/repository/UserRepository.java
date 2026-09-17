package br.edu.utfpr.td.tsi.projeto_assinador.repository;

import br.edu.utfpr.td.tsi.projeto_assinador.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository interface for User entity.
 * Provides CRUD operations and custom queries for User data access.
 */
@Repository
public interface UserRepository extends MongoRepository<User, String> {

    /**
     * Find a user by email address.
     * 
     * @param email the email address to search for
     * @return Optional containing the user if found, empty otherwise
     */
    Optional<User> findByEmail(String email);
    
    /**
     * Find a user by email verification token.
     * 
     * @param token the verification token to search for
     * @return Optional containing the user if found, empty otherwise
     */
    Optional<User> findByEmailVerificationToken(String token);

    Optional<User> findByCpf(String cpf);

    Optional<User> findByPasswordResetToken(String token);
}
