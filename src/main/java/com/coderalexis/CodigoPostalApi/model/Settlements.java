package com.coderalexis.CodigoPostalApi.model;

import lombok.Data;

import java.io.Serializable;

@Data
public class Settlements implements Serializable {
    /**
	 * 
	 */
    private static final long serialVersionUID = 1L;
    private String name;
    private String zone_type;
    private String settlement_type;
}
