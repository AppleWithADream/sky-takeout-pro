package com.sky.mapper;

import com.sky.entity.Setmeal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SetmealMapper {

    /**
     * 根据分类id查询套餐的数量
     * @param id
     * @return
     */
    @Select("select count(id) from setmeal where category_id = #{categoryId}")
    Integer countByCategoryId(Long id);

    /**
     * 根据套餐id查询套餐信息
     * @param setmealIds
     * @return
     */
    @Select("select * from setmeal where id in (${setmealIds})")
    List<Setmeal> getSetmealsByDishIds(List<Long> setmealIds);
}
