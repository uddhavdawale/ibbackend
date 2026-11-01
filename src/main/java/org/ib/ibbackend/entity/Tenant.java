package org.ib.ibbackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "tenants", uniqueConstraints = {
        @UniqueConstraint(name = "uk_tenants_code", columnNames = "code")
})
public class Tenant {
    @Id
    @GeneratedValue
    private int id;
    private String name; // "Shreeram Stores"
    @Column(nullable = false, unique = true, length = 32)
    private String code; // "SHR"

    public String getCode() {
        return code;
    }

    public int getId() {
        return id;
    }
    public void setId(int id) {
        this.id = id;
    }

    // Keep long-based setter for internal callers while avoiding Jackson setter ambiguity.
    @JsonIgnore
    public void setId(long id) {
        this.id = Math.toIntExact(id);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setCode(String code) {
        this.code = code;
    }
}