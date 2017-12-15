package com.taobao.pamirs.transaction;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.core.PriorityOrdered;
/**
 * ��������
 * @author xuannan
 *
 */
@SuppressWarnings("serial")
public class TBTransactionHandler extends AbstractAutoProxyCreator implements BeanFactoryAware ,PriorityOrdered {
	private static transient Log log = LogFactory.getLog(TBTransactionHandler.class);
	
	/**
	 * ��Ҫ����������Ƶ�bean����
	 */
	private List<String> beanList;
	
	BeanFactory beanFactory;

	/**
	 * �����Ƿ���TBConnection�л�ȡSessionId
	 * @param isSetConnectionInfo
	 */
	public void setSetConnectionInfo(boolean isSetConnectionInfo) {
		 TBConnection.isSetConnectionInfo = isSetConnectionInfo;
	}
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}
	
	public void setBeanList(List<String> beanList) {
		this.beanList = beanList;
	}
	
	public TBTransactionHandler(){
		//��������ȼ����ظô���
		this.setOrder(HIGHEST_PRECEDENCE);
	}
	
	@SuppressWarnings({"rawtypes" })
	protected Object[] getAdvicesAndAdvisorsForBean(Class beanClass,
			String beanName, TargetSource targetSource) throws BeansException {
        if (isAnnotationPresent(beanClass,TBTransactionAnnotation.class)
        		|| TBTransactionHint.class.isAssignableFrom(beanClass)
        		|| (this.beanList != null && this.beanList.contains(beanName))) {
			if (log.isDebugEnabled()) {
				log.debug("�������" + beanClass + ":" + beanName);
			}
			/**
			 * ����Ҫ��������������жϣ������������ķ������η���final������ʹ��PROXY������
			 * ��������������Ѿ���PROXY��������������η�Ҳ��final
			 * ������ڱ����������ڲ��࣬����Ϊfinal�ģ��ñ��������Կ���ʹ��CGLIB����
			 */
			if(targetBeanIsFinal(beanClass)){
				this.setProxyTargetClass(false);//PROXY
			}else{
				this.setProxyTargetClass(true);//CGLIB
			}
			return new TransactionAdvisor[]{new TransactionAdvisor(beanClass)};
		}
		return DO_NOT_PROXY;
	}
	public static boolean isAnnotationPresent(Class<?> aClass,Class<? extends Annotation> annotationClass){
		while (aClass != null) {
			if (aClass.isAnnotationPresent(annotationClass)) {
				return true;
			}
			for (Class<?> interfaceClass : aClass.getInterfaces()) {
				if (interfaceClass.isAnnotationPresent(annotationClass)) {
					return true;
				}
			}
			aClass = aClass.getSuperclass();
		}
		return false;
	}	
	private boolean targetBeanIsFinal(Class<?> clazz){
		String inMods = Modifier.toString(clazz.getModifiers());
    	if(inMods.contains("final")){
    		return true;
    	}else{
    		return false;
    	}
	}
	
}

class TransactionAdvisor implements Advisor {
 
	TransactionRoundAdvice advice;
	TransactionAdvisor(Class<?> aBeanClass){
		advice = new TransactionRoundAdvice(aBeanClass);
	}
	public Advice getAdvice() {
		return advice;
	}

	public boolean isPerInstance() {
		return false;
	}
}



 
class ReflectMethodAction implements TBMethodAction {

	private MethodInvocation invocation;

	public ReflectMethodAction(MethodInvocation invocation) {
		this.invocation = invocation;
	}

	public Object proceed() throws Throwable {
		return invocation.proceed();
	}

	public String getMethodName() throws Throwable {
		return invocation.getMethod().getDeclaringClass().getName() + "."
				+ invocation.getMethod().getName();
	}

}


class TransactionRoundAdvice implements MethodInterceptor, Advice {
	private static transient Log log = LogFactory
			.getLog(TransactionRoundAdvice.class);
    private Class<?> beanClass; 
    TransactionRoundAdvice(Class<?> aBeanClass){
    	this.beanClass = aBeanClass;
    }
	public Object invoke(MethodInvocation invocation) throws Throwable {
		TBTransactionType transactionType = getTBTransactionType(invocation.getMethod());
		return invokeInner(new ReflectMethodAction(invocation),transactionType);
	}
	
		
	public TBTransactionType getTBTransactionType(Method interfaceMethod) throws Exception {
		//�ȿ��ӿ����Ƿ�����Annotation
		TBTransactionTypeAnnotation transactionTypeAnn = interfaceMethod.getAnnotation(TBTransactionTypeAnnotation.class);
		if(transactionTypeAnn == null){		
			//��ʵ�ʵ�ʵ�����ϻ�ȡ����Annotation
			Method realMethod = this.beanClass.getMethod(interfaceMethod.getName(),interfaceMethod.getParameterTypes());
			transactionTypeAnn = realMethod.getAnnotation(TBTransactionTypeAnnotation.class);
		}	
		if (transactionTypeAnn != null) {
			return transactionTypeAnn.value();
		} else {
			return TBTransactionType.JOIN;
		}
	}

	
	public static Object invokeInner(MethodInvocation invocation,TBTransactionType transactionType) throws Throwable {
		return invokeInner(new ReflectMethodAction(invocation), transactionType);
	}
	
	
	public static Object invokeInner(TBMethodAction methodAction,TBTransactionType transactionType) throws Throwable {
		String methodName =methodAction.getMethodName();
		Object result = null;
		long startTime = System.currentTimeMillis();
		// ִ��Ҫ���������ԭ������
		boolean isSelfStartTransaction = false;
		boolean isSuspend = false;
		boolean isStartTransactionInParent = TransactionManager
				.getTransactionManager().isStartTransaction();
		try {
			if (transactionType == TBTransactionType.JOIN) {// ��������
				isSuspend = false;
				if (isStartTransactionInParent == false) {// �ڸ�������û�п�ʼ����
					isSelfStartTransaction = true;
					TransactionManager.getTransactionManager().begin();
				} else {
					isSelfStartTransaction = false;
				}
			} else {// ��������
				if (isStartTransactionInParent == false) {// �ڸ�������û�п�ʼ����,����Ҫ�����������
					TransactionManager.getTransactionManager().begin();
					isSuspend = false;
					isSelfStartTransaction = true;
				} else {// �����ⲿ�����,��ʼ�µ�����
					TransactionManager.getTransactionManager().suspend();
					isSuspend = true;
					TransactionManager.getTransactionManager().begin();
					isSelfStartTransaction = true;
				}
			}

			if (log.isDebugEnabled()) {
				log.debug(methodName + ": suspendTransaction = "
						+ isSuspend + ", startTransaction = "
						+ isSelfStartTransaction);
			}
			result = methodAction.proceed();
			if (isSelfStartTransaction == true) {// �Լ���ʼ��,���ύ����
				TransactionManager.getTransactionManager().commit();
				if (log.isDebugEnabled()) {
					log.debug(methodName + ": commitTransaction ");
				}
			}
		} catch (Throwable e) {
			try {
				if (isSelfStartTransaction == true) {// �Լ���ʼ��,��ع�����
					TransactionManager.getTransactionManager().rollback();
					if (log.isDebugEnabled()) {
						log.debug(methodName + ": rollbackTransaction ");
					}
				} else {// ��������Ϊֻ�ܻع�
					TransactionManager.getTransactionManager().setRollbackOnly();
					if (log.isDebugEnabled()) {
						log.debug(methodName + ": setRollbackOnly ");
					}
				}
			} catch (Throwable ex) {
				log.fatal("�ع�����ʧ��", ex);
			}
			throw e;
		} finally {
			try {
				if (isSuspend == true) {// �ָ����������
					TransactionManager.getTransactionManager().resume();
					if (log.isDebugEnabled()) {
						log.debug(methodName + ": resumeTransaction ");
					}
				}
			} catch (Throwable ex) {
				log.fatal("�ָ����������ʧ��", ex);
			}
			if (log.isDebugEnabled()) {
				log.debug("execute " + methodName + " ��ʱ(ms):"
						+ (System.currentTimeMillis() - startTime));
			}
		}
		return result;
	}
}