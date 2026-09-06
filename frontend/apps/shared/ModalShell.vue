<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
const props=defineProps<{busy:boolean}>();const emit=defineEmits<{close:[]}>();const dialog=ref<HTMLDialogElement>();
onMounted(()=>dialog.value?.showModal());onBeforeUnmount(()=>dialog.value?.close());
function cancel(event:Event){event.preventDefault();if(!props.busy)emit('close');}
</script>
<template><dialog ref="dialog" class="workflow-native-modal" role="presentation" @cancel="cancel"><slot /></dialog></template>
