package com.example.knowledge_system.mapper;

import com.example.knowledge_system.entity.CustomerOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomerOrderMapper {

    List<CustomerOrder> findByUserId(@Param("userId") Long userId);

    CustomerOrder findByOrderNo(@Param("orderNo") String orderNo);
}