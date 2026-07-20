package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.CategoryPageQueryDTO;
import com.sky.entity.Category;
import com.sky.enumeration.OperationType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryMapper {
    /**
     * 分页查询分类
     * @param categoryPageQueryDTO
     * @return
     */
    Page<Category> pageQuery(CategoryPageQueryDTO categoryPageQueryDTO);

    /**
     * 启用禁用分类
     * @param category
     */
    @AutoFill(value = OperationType.UPDATE)
    void update(Category category);

    /**
     * 新增分类
     * @param category
     */
    @Insert("INSERT INTO category (name, type, status, sort, create_time, update_time, create_user, update_user) " +
            "VALUES (#{name}, #{type}, #{status}, #{sort}, #{createTime}, #{updateTime}, #{createUser}, #{updateUser})")
    @AutoFill(value = OperationType.INSERT)
    void insert(Category category);

    /**
     * 删除分类
     * @param id
     */
    @Delete("DELETE FROM category WHERE id = #{id}")
    void delete(Long id);

    /**
     * 根据id查询分类
     * @param id
     * @return
     */
    @Select("SELECT * FROM category WHERE id = #{id}")
    Category getById(Long id);

    /**
     * 根据类型查询分类列表
     * @param type
     * @return
     */
    @Select("SELECT * FROM category WHERE type = #{type}")
    List<Category> getList(Long type);
}
