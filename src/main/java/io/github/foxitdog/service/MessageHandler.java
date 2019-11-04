package io.github.foxitdog.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class MessageHandler {

    public Object handleMessage(Object msg){
        log.info(msg);
        return "hello world!";
    }

}
