package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SetmealDishMapper {

    /**
    * 根据菜品id列表查询对应的套餐id
    * @param dishIds
    * @return
    */
    List<Long> getSetmealIdsByDishIds(List<Long> dishIds);

    /**
     * 根据菜品id查询对应的套餐id列表
     * @param dishId
     * @return
     */
    @Select("select setmeal_id from setmeal_dish where dish_id = #{dishId}")
    List<Long> getSetmealIdsByDishId(Long dishId);
}
