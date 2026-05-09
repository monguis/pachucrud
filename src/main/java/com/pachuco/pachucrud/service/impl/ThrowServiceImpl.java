package com.pachuco.pachucrud.service.impl;

import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.pachuco.pachucrud.model.ThrowModel;
import com.pachuco.pachucrud.sevice.ThrowService;

@Service
public class ThrowServiceImpl implements ThrowService{
    Logger logger = LoggerFactory.getLogger(ThrowServiceImpl.class);

    public ThrowModel generaThrowModel() throws Exception {
        Random random = new Random();
        
        Integer[] diceThrow = new Integer[] {
            random.nextInt(6) + 1,
            random.nextInt(6) + 1,
            random.nextInt(6) + 1,
            random.nextInt(6) + 1,
            random.nextInt(6) + 1,
        };
        
        ThrowModel resp = new ThrowModel(diceThrow);

        return resp;
    }

    

}
