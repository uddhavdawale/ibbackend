package org.ib.ibbackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Tenant {
    @Id
    @GeneratedValue
    private int id;
    private String name; // "Shreeram Stores"
    @Column(nullable = false, unique = true, length = 32)
    private String code; // "SHR"

    @Column(length = 500)
    private String address;

    @Column(length = 100)
    private String city;

    @Column(length = 20)
    private String pincode;

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

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }
}