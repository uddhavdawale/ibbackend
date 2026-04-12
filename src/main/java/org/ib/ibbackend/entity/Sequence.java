package org.ib.ibbackend.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Entity
@Table(name = "sequences")
public class Sequence {
    @Id
    private String name;

    private int value = 0;

    // Constructors, getters, setters
    public Sequence() {}
    public Sequence(String name) { this.name = name; }

    public int nextValue() {
        this.value++;
        return this.value;
    }
    // getters/setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }
}

