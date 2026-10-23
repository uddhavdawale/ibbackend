package org.ib.ibbackend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SequenceService {
    private static final Logger log = LoggerFactory.getLogger(SequenceService.class);

    @Autowired private JdbcTemplate jdbcTemplate;

    @Transactional
    public synchronized int nextValue(String seqName) {
        try {
            Integer current = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(value, 0) FROM sequences WHERE name = ? FOR UPDATE",
                    Integer.class,
                    seqName
            );
            int next = (current != null ? current : 0) + 1;
            jdbcTemplate.update("INSERT INTO sequences (name, value) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET value = ?",
                    seqName, next, next);
            log.debug("Generated next sequence value for {} = {}", seqName, next);
            return next;
        } catch (EmptyResultDataAccessException e) {
            log.warn("Sequence {} was missing, creating it with initial value 1", seqName, e);
            jdbcTemplate.update("INSERT INTO sequences (name, value) VALUES (?, 1)", seqName);
            return 1;
        }
    }
}