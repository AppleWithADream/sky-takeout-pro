package com.sky.dto;

import lombok.Data;
import java.io.Serializable;

/**
* 购物车数据
*/

@Data
public class ShoppingCartDTO implements Serializable {

    private Long dishId;//菜品id
    private Long setmealId;//套餐id
    private String dishFlavor;//菜品口味

}
