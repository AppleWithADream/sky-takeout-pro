package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.CategoryDTO;
import com.sky.dto.CategoryPageQueryDTO;
import com.sky.entity.Category;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.CategoryService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 分类分页查询
     *
     * @param categoryPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(CategoryPageQueryDTO categoryPageQueryDTO) {

        PageHelper.startPage(categoryPageQueryDTO.getPage(), categoryPageQueryDTO.getPageSize());
        Page<CategoryPageQueryDTO> page = categoryMapper.pageQuery(categoryPageQueryDTO);

        Long total = page.getTotal();
        List<CategoryPageQueryDTO> records = page.getResult();
        return new PageResult(total, records);
    }

    /**
     * 启用禁用分类
     * @param status
     * @param id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        Category category = Category.builder()
                .status(status)
                .id(id)
                .updateTime(LocalDateTime.now())
                .build();

        categoryMapper.update(category);
    }

    /**
     * 修改分类
     * @param categoryDTO
     */
    @Override
    public void update(CategoryDTO categoryDTO) {

        Category category = new Category();
        BeanUtils.copyProperties(categoryDTO, category);
        // 设置更新时间
        category.setUpdateTime(LocalDateTime.now());
        // 设置更新人
        category.setUpdateUser(BaseContext.getCurrentId());

        categoryMapper.update(category);
    }

    /**
     * 新增分类
     * @param categoryDTO
     */
    @Override
    public void save(CategoryDTO categoryDTO) {
        Category category = new Category();
        BeanUtils.copyProperties(categoryDTO, category);
        category.setStatus(StatusConstant.DISABLE);

        category.setCreateTime(LocalDateTime.now());
        category.setUpdateTime(LocalDateTime.now());

        category.setCreateUser(BaseContext.getCurrentId());
        category.setUpdateUser(BaseContext.getCurrentId());
        categoryMapper.insert(category);
    }

    /**
     * 删除分类
     * @param id
     */
    @Override
    public void delete(Long id) {
        Category category = categoryMapper.getById(id);
        if (category.getType() == 1){
            //菜品分类：查菜品表
            Integer dishCount = dishMapper.countByCategoryId(id);
            if(dishCount > 0){
                throw new RuntimeException("该分类下关联菜品，无法删除");
            }
        }else {
            //套餐分类：查套餐表
            Integer setmealCount = setmealMapper.countByCategoryId(id);
            if(setmealCount > 0){
                throw new RuntimeException("该分类下关联套餐，无法删除");
            }
        }
    }

    /**
     * 根据类型查询分类列表
     * @param type
     * @return
     */
    @Override
    public List<Category> getList(Long type) {
        return categoryMapper.getList(type);
    }
}
