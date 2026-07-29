package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;

    /**
     * 新增套餐
     * @param setmealDTO
     */
    @Transactional
    @Override
    public void save(SetmealDTO setmealDTO) {
        // 将setmealDTO中的属性复制到setmeal中
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        setmeal.setStatus(StatusConstant.ENABLE);
        // 获取新增套餐的id
        setmealMapper.save(setmeal);
        Long smealId = setmeal.getId(); // 从对象里面拿回填的id
        // 获取套餐中的菜品列表
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && setmealDishes.size() > 0){
            for (SetmealDish setmealDish : setmealDishes) {
                setmealDish.setSetmealId(smealId);
            }
            // 批量保存套餐与菜品的关联关系
            setmealDishMapper.saveBatch(setmealDishes);
        }
    }

    /**
     * 套餐分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        // PageHelper分页核心规则：【紧挨着数据库查询方法前面】调用startPage
        // 参数1：当前页码 page；参数2：每页条数 pageSize
        // 原理：使用ThreadLocal保存分页参数，自动拦截下一条SQL，拼接limit
        PageHelper.startPage(setmealPageQueryDTO.getPage(), setmealPageQueryDTO.getPageSize());
        // 执行查询SQL，此时MyBatis会被PageHelper拦截，自动加上limit实现分页
        // 返回的Page对象是PageHelper封装类型，里面包含总数、当前页数据
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);

        // 获取满足条件的【总记录数】，用于前端渲染分页组件（总条数）
        Long total = page.getTotal();
        // 获取当前页查询出来的数据列表
        List<SetmealVO> records = page.getResult();

        // 封装苍穹外卖统一分页返回结果，传给前端
        return new PageResult(total, records);
    }

    /**
     * 批量删除套餐
     * @param ids
     */
    @Transactional
    @Override
    public void delete(List<Long> ids) {
        List<Setmeal> setmeals = setmealMapper.getSetmealsByDishIds(ids);
        for (Setmeal setmeal : setmeals) {
            if (setmeal.getStatus() == StatusConstant.ENABLE) {
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        }
        // 批量删除套餐
        setmealMapper.delete(ids);
        // 批量删除套餐与菜品的关联关系
        setmealDishMapper.deleteBySetmealIds(ids);
    }

    /**
     * 根据id查询套餐
     * @param id
     * @return
     */
    @Override
    public SetmealVO getById(Long id) {
        //根据id查询套餐基本信息
        Setmeal setmeal = setmealMapper.getById(id);
        // 创建SetmealVO对象
        SetmealVO setmealVO = new SetmealVO();
        // 将setmeal中的属性复制到setmealVO中
        BeanUtils.copyProperties(setmeal, setmealVO);
        // 根据套餐id查询套餐中的菜品列表
        setmealVO.setSetmealDishes(setmealDishMapper.getBySetmealId(id));
        return setmealVO;
    }

    /**
     * 修改套餐
     * @param setmealDTO
     */
    @Override
    public void update(SetmealDTO setmealDTO) {
        // 创建Setmeal对象
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        // 更新套餐信息
        setmealMapper.update(setmeal);

        Long id = setmeal.getId();
        // 根据套餐id删除套餐与菜品的关联关系
        setmealDishMapper.deleteBySetmealId(id);

        //获取套餐中的菜品列表
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        //设置套餐id
        for (SetmealDish setmealDish : setmealDishes){
            setmealDish.setSetmealId(id);
        }
        // 批量保存套餐与菜品的关联关系
        setmealDishMapper.saveBatch(setmealDishes);
    }

    /**
     * 套餐套餐起售与停售
     * @param status
     * @param id
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        if(status == StatusConstant.DISABLE){
            // 判断套餐中的菜品是否都处于停售状态
            List<SetmealDish> setmealDishes = setmealDishMapper.getBySetmealId(id);
            List<Dish> dishes = dishMapper.getBySetmealDishIds(id);
            if (dishes != null && dishes.size() > 0) {
                for (Dish dish : dishes){
                    if (dish.getStatus() == StatusConstant.DISABLE) {
                        throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                }
            }
        }
        // 更新套餐状态
        Setmeal setmeal = Setmeal.builder()
                .id(id)
                .status(status)
                .build();
        setmealMapper.update(setmeal);
    }

}







